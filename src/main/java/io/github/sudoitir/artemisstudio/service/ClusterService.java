package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.BrokerCapabilities;
import io.github.sudoitir.artemisstudio.broker.BrokerClientFactory;
import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.BrokerConnectionSettings;
import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.CapabilityProbe;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.core.CorePool;
import io.github.sudoitir.artemisstudio.broker.core.CoreSubscriptionManager;
import io.github.sudoitir.artemisstudio.broker.core.SubscriptionVerdict;
import io.github.sudoitir.artemisstudio.domain.topology.ClusterTopology;
import io.github.sudoitir.artemisstudio.domain.topology.HaStateEvaluator;
import io.github.sudoitir.artemisstudio.domain.topology.NodeEndpoint;
import io.github.sudoitir.artemisstudio.domain.topology.SplitBrainRegistry;
import io.github.sudoitir.artemisstudio.domain.topology.TopologyDiscovery;
import io.github.sudoitir.artemisstudio.domain.topology.TopologyDiscovery.ProbedSeed;
import io.github.sudoitir.artemisstudio.mapper.BrokerNodeMapper;
import io.github.sudoitir.artemisstudio.mapper.ClusterViewMapper;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.persist.BrokerCredentialEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerCredentialRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerTlsEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerTlsRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.security.SecretVault;
import io.github.sudoitir.artemisstudio.web.dto.ClusterRequests.NodeOverrideRequest;
import io.github.sudoitir.artemisstudio.web.dto.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.CapabilitiesView;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.ClusterDetail;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.ClusterSummary;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.HealthView;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.NodeEndpointView;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.RegisterPreview;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.TopologyView;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates cluster registration, topology, capabilities, and health.
 *
 * <p>Every mutating method opens its transaction, writes a {@code PENDING} audit
 * row, then commits with the audit row set to {@code SUCCESS} or {@code FAILURE}
 * (non-negotiable #3). A broker that cannot be reached is a {@link Attempt.Failed}
 * return, not a rollback — the audit trail keeps the failed attempt.
 */
@Service
@RequiredArgsConstructor
public class ClusterService {

    private static final String JOLOKIA_BASIC = "JOLOKIA_BASIC";
    private static final String CORE = "CORE";
    private static final UUID UNBOUND = new UUID(0L, 0L);

    private final ClusterRepository clusters;
    private final BrokerNodeRepository nodes;
    private final BrokerCredentialRepository credentials;
    private final BrokerTlsRepository tlsRepository;

    private final BrokerClientFactory clientFactory;
    private final BrokerConnections connections;
    private final CapabilityProbe capabilityProbe;
    private final TopologyDiscovery topologyDiscovery;
    private final HaStateEvaluator evaluator;
    private final SplitBrainRegistry splitBrainRegistry;
    private final CoreSubscriptionManager coreSubscriptions;
    private final CorePool corePool;
    private final SecretVault vault;
    private final AuditService audit;
    private final io.github.sudoitir.artemisstudio.security.ActorResolver actorResolver;

    private final BrokerNodeMapper nodeMapper;
    private final ClusterViewMapper viewMapper;

    private record Probe(String url, JolokiaBrokerClient client, BrokerConnectionException error) {
        boolean ok() {
            return error == null;
        }

        ProbedSeed asSeed() {
            return new ProbedSeed(url, client);
        }
    }

    // ---- connection check (?dryRun=true) -------------------------------------

    @Transactional
    public Attempt<RegisterPreview> checkConnection(RegisterClusterRequest request) {
        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "REGISTER_CLUSTER",
                "CLUSTER",
                request.name(),
                null,
                null,
                Map.of("seedUrls", request.seedUrls()),
                true);

        List<Probe> probes = connectAll(request);
        List<Probe> reachable = probes.stream().filter(Probe::ok).toList();
        if (reachable.isEmpty()) {
            return failed(event, probes.get(0).error());
        }

        BrokerCapabilities capabilities =
                capabilityProbe.probe(reachable.get(0).client(), new SubscriptionVerdict.NotAttempted());
        ClusterTopology preview =
                topologyDiscovery.preview(reachable.stream().map(Probe::asSeed).toList());
        List<String> names = preview.nodes().stream()
                .flatMap(n -> n.endpoints().stream())
                .map(NodeEndpoint::name)
                .distinct()
                .toList();

        audit.succeed(event, names.size());
        return new Attempt.Ok<>(
                new RegisterPreview(viewMapper.capabilities(capabilities), reachable.size(), names.size(), names));
    }

    // ---- registration -------------------------------------------------------

    @Transactional
    public Attempt<ClusterDetail> register(RegisterClusterRequest request) {
        List<Probe> probes = connectAll(request);
        List<Probe> reachable = probes.stream().filter(Probe::ok).toList();
        if (reachable.isEmpty()) {
            AuditEventEntity event = audit.begin(
                    actorResolver.resolve(),
                    "REGISTER_CLUSTER",
                    "CLUSTER",
                    request.name(),
                    null,
                    null,
                    Map.of(),
                    false);
            return failed(event, probes.get(0).error());
        }

        ClusterEntity cluster = clusters.save(new ClusterEntity(
                request.name() != null
                        ? request.name()
                        : hostOf(request.seedUrls().get(0)),
                request.description(),
                null));
        UUID clusterId = cluster.getId();

        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "REGISTER_CLUSTER",
                "CLUSTER",
                cluster.getName(),
                clusterId,
                null,
                Map.of("seedUrls", request.seedUrls()),
                false);

        if (request.hasCredentials()) {
            SecretVault.Sealed sealed = vault.encrypt(
                    clusterId, JOLOKIA_BASIC, request.credentials().password());
            credentials.save(new BrokerCredentialEntity(
                    clusterId, JOLOKIA_BASIC, request.credentials().username(), sealed.ciphertext(), sealed.nonce()));
        }
        if (request.hasCoreCredentials()) {
            // A CORE row is a genuinely separate sealed secret — AAD is clusterId|CORE
            // (ADR-0026). When absent, coreSettingsFor falls back to the Jolokia credential.
            SecretVault.Sealed sealed =
                    vault.encrypt(clusterId, CORE, request.coreCredentials().password());
            credentials.save(new BrokerCredentialEntity(
                    clusterId, CORE, request.coreCredentials().username(), sealed.ciphertext(), sealed.nonce()));
        }
        if (request.tlsBundle() != null) {
            tlsRepository.save(new BrokerTlsEntity(clusterId, request.tlsBundle(), null, true));
        }

        ClusterTopology topology = topologyDiscovery.discover(
                clusterId, reachable.stream().map(Probe::asSeed).toList());
        BrokerCapabilities capabilities =
                capabilityProbe.probe(reachable.get(0).client(), coreSubscriptions.verdictFor(clusterId));

        audit.succeed(event, endpointCount(topology));
        return new Attempt.Ok<>(new ClusterDetail(
                clusterId,
                cluster.getName(),
                cluster.getDescription(),
                viewMapper.topology(topology),
                viewMapper.capabilities(capabilities),
                viewMapper.health(evaluator.toHealth(clusterId, topology.nodes()))));
    }

    // ---- reads ------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ClusterSummary> list() {
        List<ClusterSummary> out = new ArrayList<>();
        for (ClusterEntity c : clusters.findAllByOrderByNameAsc()) {
            List<BrokerNodeEntity> rows = nodes.findByClusterIdOrderByNameAsc(c.getId());
            var logical =
                    evaluator.toLogicalNodes(nodeMapper.toEndpoints(rows), splitBrainRegistry.statusesFor(c.getId()));
            var health = evaluator.toHealth(c.getId(), logical);
            out.add(new ClusterSummary(
                    c.getId(), c.getName(), c.getDescription(), health.level(), rows.size(), c.getUpdatedAt()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ClusterDetail get(UUID clusterId) {
        ClusterEntity cluster = requireCluster(clusterId);
        ClusterTopology topology = topologyDiscovery.currentTopology(clusterId);
        return new ClusterDetail(
                clusterId,
                cluster.getName(),
                cluster.getDescription(),
                viewMapper.topology(topology),
                capabilities(clusterId),
                viewMapper.health(evaluator.toHealth(clusterId, topology.nodes())));
    }

    @Transactional(readOnly = true)
    public TopologyView topology(UUID clusterId) {
        requireCluster(clusterId);
        return viewMapper.topology(topologyDiscovery.currentTopology(clusterId));
    }

    @Transactional(readOnly = true)
    public HealthView health(UUID clusterId) {
        requireCluster(clusterId);
        ClusterTopology topology = topologyDiscovery.currentTopology(clusterId);
        return viewMapper.health(evaluator.toHealth(clusterId, topology.nodes()));
    }

    /** A live probe of the first manageable node (ADR: no capability cache in Phase 1). */
    @Transactional(readOnly = true)
    public CapabilitiesView capabilities(UUID clusterId) {
        requireCluster(clusterId);
        BrokerNodeEntity manageable = nodes.findByClusterIdOrderByNameAsc(clusterId).stream()
                .filter(n -> n.getJolokiaUrl() != null)
                .findFirst()
                .orElseThrow(() -> new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNREACHABLE,
                        "This cluster has no node with a management URL yet."));
        JolokiaBrokerClient client = connections.forCluster(clusterId, manageable.getJolokiaUrl());
        return viewMapper.capabilities(capabilityProbe.probe(client, coreSubscriptions.verdictFor(clusterId)));
    }

    // ---- mutations ------------------------------------------------------------

    @Transactional
    public Attempt<TopologyView> rediscover(UUID clusterId) {
        ClusterEntity cluster = requireCluster(clusterId);
        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "REDISCOVER_CLUSTER",
                "CLUSTER",
                cluster.getName(),
                clusterId,
                null,
                Map.of(),
                false);

        List<ProbedSeed> seeds = new ArrayList<>();
        for (BrokerNodeEntity node : nodes.findByClusterIdOrderByNameAsc(clusterId)) {
            if (node.getJolokiaUrl() == null) {
                continue;
            }
            try {
                JolokiaBrokerClient client = connections.forCluster(clusterId, node.getJolokiaUrl());
                client.resolveBrokerObjectName();
                seeds.add(new ProbedSeed(node.getJolokiaUrl(), client));
            } catch (BrokerConnectionException ignored) {
                // Skip a node that is unreachable this round; the refresh loop records its error.
            }
        }
        if (seeds.isEmpty()) {
            return failed(event, BrokerConnectionException.of(BrokerConnectionException.Kind.UNREACHABLE));
        }

        ClusterTopology topology = topologyDiscovery.discover(clusterId, seeds);
        cluster.touch();
        audit.succeed(event, endpointCount(topology));
        return new Attempt.Ok<>(viewMapper.topology(topology));
    }

    @Transactional
    public Attempt<NodeEndpointView> overrideNodeUrl(UUID clusterId, UUID nodeId, NodeOverrideRequest request) {
        requireCluster(clusterId);
        BrokerNodeEntity node = nodes.findById(nodeId)
                .filter(n -> n.getClusterId().equals(clusterId))
                .orElseThrow(() -> new NotFoundException("Node", nodeId));

        Map<String, Object> params = new HashMap<>();
        if (request.hasJolokiaUrl()) {
            params.put("jolokiaUrl", request.jolokiaUrl());
        }
        if (request.hasCoreUrl()) {
            params.put("coreUrl", request.coreUrl());
        }
        AuditEventEntity event = audit.begin(
                actorResolver.resolve(), "OVERRIDE_NODE_URL", "NODE", node.getName(), clusterId, nodeId, params, false);

        if (request.hasJolokiaUrl()) {
            try {
                connections.forCluster(clusterId, request.jolokiaUrl()).resolveBrokerObjectName();
            } catch (BrokerConnectionException e) {
                return failed(event, e);
            }
            node.applyManualUrl(request.jolokiaUrl());
        }
        if (request.hasCoreUrl()) {
            node.applyManualCoreUrl(request.coreUrl());
        }
        nodes.save(node);
        audit.succeed(event, 1);
        return new Attempt.Ok<>(viewMapper.endpoint(nodeMapper.toEndpoint(node)));
    }

    /** Rotate a stored credential (JOLOKIA_BASIC or CORE) for a cluster; re-encrypts, audits in-transaction, returns nothing secret. */
    @Transactional
    public void rotateCredentials(UUID clusterId, String username, String password, String kind) {
        ClusterEntity cluster = requireCluster(clusterId);
        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "ROTATE_CREDENTIALS",
                "CLUSTER",
                cluster.getName(),
                clusterId,
                null,
                Map.of("username", username, "kind", kind),
                false);

        SecretVault.Sealed sealed = vault.encrypt(clusterId, kind, password);
        credentials
                .findByClusterIdAndKind(clusterId, kind)
                .ifPresentOrElse(
                        existing -> existing.replaceSecret(username, sealed.ciphertext(), sealed.nonce()),
                        () -> credentials.save(new BrokerCredentialEntity(
                                clusterId, kind, username, sealed.ciphertext(), sealed.nonce())));

        audit.succeed(event, 1);
    }

    @Transactional
    public void delete(UUID clusterId) {
        ClusterEntity cluster = requireCluster(clusterId);
        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "DELETE_CLUSTER",
                "CLUSTER",
                cluster.getName(),
                clusterId,
                null,
                Map.of(),
                false);
        clusters.delete(cluster);
        // Release Core connections and drop the in-memory subscription state so a
        // removed cluster is not retried.
        coreSubscriptions.forget(clusterId);
        corePool.forget(clusterId);
        audit.succeed(event, 1);
    }

    // ---- helpers ------------------------------------------------------------

    private List<Probe> connectAll(RegisterClusterRequest request) {
        BrokerConnectionSettings settings = new BrokerConnectionSettings(
                UNBOUND,
                request.hasCredentials() ? request.credentials().username() : null,
                request.hasCredentials() ? request.credentials().password() : null,
                request.tlsBundle(),
                true);

        List<Probe> probes = new ArrayList<>();
        for (String url : request.seedUrls()) {
            try {
                JolokiaBrokerClient client = clientFactory.forNode(settings, url);
                client.resolveBrokerObjectName();
                probes.add(new Probe(url, client, null));
            } catch (BrokerConnectionException e) {
                probes.add(new Probe(url, null, e));
            }
        }
        return probes;
    }

    private <T> Attempt<T> failed(AuditEventEntity event, BrokerConnectionException error) {
        audit.fail(event, error.getMessage());
        return new Attempt.Failed<>(error.kind(), error.getMessage());
    }

    private ClusterEntity requireCluster(UUID clusterId) {
        return clusters.findById(clusterId).orElseThrow(() -> new NotFoundException("Cluster", clusterId));
    }

    private static int endpointCount(ClusterTopology topology) {
        return topology.nodes().stream().mapToInt(n -> n.endpoints().size()).sum();
    }

    private static String hostOf(String url) {
        try {
            URI u = URI.create(url);
            return u.getPort() > 0 ? u.getHost() + ":" + u.getPort() : u.getHost();
        } catch (RuntimeException e) {
            return url;
        }
    }
}
