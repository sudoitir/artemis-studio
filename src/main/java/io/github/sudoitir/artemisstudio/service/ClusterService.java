package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.BrokerCapabilities;
import io.github.sudoitir.artemisstudio.broker.BrokerClientFactory;
import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.BrokerConnectionSettings;
import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.CapabilityProbe;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.brokerconfig.BrokerConfigOperations;
import io.github.sudoitir.artemisstudio.broker.core.CoreConnectionSettings;
import io.github.sudoitir.artemisstudio.broker.core.CorePool;
import io.github.sudoitir.artemisstudio.broker.core.CoreSubscriptionCheck;
import io.github.sudoitir.artemisstudio.broker.core.CoreSubscriptionManager;
import io.github.sudoitir.artemisstudio.broker.core.SubscriptionVerdict;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.ObservedNodeConfig;
import io.github.sudoitir.artemisstudio.domain.topology.ClusterTopology;
import io.github.sudoitir.artemisstudio.domain.topology.HaStateEvaluator;
import io.github.sudoitir.artemisstudio.domain.topology.NodeEndpoint;
import io.github.sudoitir.artemisstudio.domain.topology.SplitBrainRegistry;
import io.github.sudoitir.artemisstudio.domain.topology.TopologyDiscovery;
import io.github.sudoitir.artemisstudio.domain.topology.TopologyDiscovery.ProbedSeed;
import io.github.sudoitir.artemisstudio.mapper.BrokerNodeMapper;
import io.github.sudoitir.artemisstudio.mapper.ClusterViewMapper;
import io.github.sudoitir.artemisstudio.persist.AlertRuleEntity;
import io.github.sudoitir.artemisstudio.persist.AlertRuleRepository;
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
import io.github.sudoitir.artemisstudio.security.ClusterEnvironmentIndex;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.security.SecretVault;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigViews;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final BrokerConfigOperations brokerConfigOperations;
    private final CapabilityProbe capabilityProbe;
    private final CapabilityLedger capabilityLedger;
    private final TopologyDiscovery topologyDiscovery;
    private final HaStateEvaluator evaluator;
    private final SplitBrainRegistry splitBrainRegistry;
    private final CoreSubscriptionManager coreSubscriptions;
    private final CoreSubscriptionCheck coreSubscriptionCheck;
    private final CorePool corePool;
    private final SecretVault vault;
    private final AuditService audit;
    private final AlertRuleRepository alertRules;
    private final io.github.sudoitir.artemisstudio.security.ActorResolver actorResolver;
    private final ClusterEnvironmentIndex environmentIndex;
    private final ClusterAccessGuard clusterAccess;

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

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).CLUSTER_WRITE)")
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

        ClusterTopology preview =
                topologyDiscovery.preview(reachable.stream().map(Probe::asSeed).toList());

        // Actually open a Core subscription rather than reporting NotAttempted. A
        // check that stays silent about the Core channel is how a wrong Core account
        // — or a management account the broker reserves as its <cluster-user> —
        // reaches a registered cluster and fails there instead, where the operator
        // has no obvious way back.
        SubscriptionVerdict coreVerdict = coreSubscriptionCheck.probe(
                preview.nodes().stream().flatMap(n -> n.endpoints().stream()).toList(), coreSettingsFrom(request));
        BrokerCapabilities capabilities = capabilityProbe.probe(reachable.get(0).client(), coreVerdict);
        int nodeCount = (int) preview.nodes().stream()
                .flatMap(n -> n.endpoints().stream())
                .map(NodeEndpoint::name)
                .distinct()
                .count();

        audit.succeed(event, nodeCount);
        return new Attempt.Ok<>(new RegisterPreview(
                viewMapper.capabilities(capabilities),
                reachable.size(),
                nodeCount,
                viewMapper.topology(preview),
                BrokerConfigViews.RecommendationsView.of(
                        BrokerConfigRecommendations.from(capabilities, checkSeed(reachable.get(0))))));
    }

    /**
     * What the checked node is running, so the recommendations shown before
     * registration are the same ones shown after it — seeded, not generic. A read
     * that fails costs the seed and nothing else: the panel then says it could not
     * read the node rather than showing a replace nobody can check.
     */
    private ObservedNodeConfig checkSeed(Probe probe) {
        try {
            return brokerConfigOperations.read(
                    probe.client(),
                    new UUID(0, 0),
                    "the checked node",
                    new BrokerConfigOperations.ReadScope(
                            Set.of("#"), Set.of("#", "activemq.notifications"), Set.of(), Map.of(), Set.of()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Core settings straight from an unregistered request: the Core credentials if
     * given, otherwise the management ones, matching the fallback
     * {@code BrokerConnections.coreSettingsFor} applies once the cluster exists
     * (ADR-0026, D6). Nothing is persisted or sealed — there is no cluster to key
     * the vault by yet.
     */
    private static CoreConnectionSettings coreSettingsFrom(RegisterClusterRequest request) {
        RegisterClusterRequest.Credentials core =
                request.hasCoreCredentials() ? request.coreCredentials() : request.credentials();
        return core == null
                ? new CoreConnectionSettings(null, null, null, request.tlsBundle(), true)
                : new CoreConnectionSettings(null, core.username(), core.password(), request.tlsBundle(), true);
    }

    // ---- registration -------------------------------------------------------

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).CLUSTER_WRITE)")
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
        BrokerCapabilities capabilities = capabilityProbe.probe(
                reachable.get(0).client(),
                coreSubscriptions.verdictFor(clusterId),
                capabilityLedger.managementWrite(clusterId));
        seedBuiltinAlertRules(clusterId);
        environmentIndex.invalidate();

        audit.succeed(event, endpointCount(topology));
        return new Attempt.Ok<>(new ClusterDetail(
                clusterId,
                cluster.getName(),
                cluster.getDescription(),
                viewMapper.topology(topology),
                viewMapper.capabilities(capabilities),
                viewMapper.health(evaluator.toHealth(clusterId, topology.nodes())),
                cluster.getEnvironmentId()));
    }

    // ---- reads ------------------------------------------------------------

    @PostFilter("@perm.can(filterObject.id(), T(io.github.sudoitir.artemisstudio.security.Permissions).CLUSTER_READ)")
    @Transactional(readOnly = true)
    public List<ClusterSummary> list() {
        List<ClusterSummary> out = new ArrayList<>();
        for (ClusterEntity c : clusters.findAllByOrderByNameAsc()) {
            List<BrokerNodeEntity> rows = nodes.findByClusterIdOrderByNameAsc(c.getId());
            var logical =
                    evaluator.toLogicalNodes(nodeMapper.toEndpoints(rows), splitBrainRegistry.statusesFor(c.getId()));
            var health = evaluator.toHealth(c.getId(), logical);
            out.add(new ClusterSummary(
                    c.getId(),
                    c.getName(),
                    c.getDescription(),
                    health.level(),
                    rows.size(),
                    c.getUpdatedAt(),
                    c.getEnvironmentId()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ClusterDetail get(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        ClusterEntity cluster = requireCluster(clusterId);
        ClusterTopology topology = topologyDiscovery.currentTopology(clusterId);
        return new ClusterDetail(
                clusterId,
                cluster.getName(),
                cluster.getDescription(),
                viewMapper.topology(topology),
                capabilities(clusterId),
                viewMapper.health(evaluator.toHealth(clusterId, topology.nodes())),
                cluster.getEnvironmentId());
    }

    @Transactional(readOnly = true)
    public TopologyView topology(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        requireCluster(clusterId);
        return viewMapper.topology(topologyDiscovery.currentTopology(clusterId));
    }

    @Transactional(readOnly = true)
    public HealthView health(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        requireCluster(clusterId);
        ClusterTopology topology = topologyDiscovery.currentTopology(clusterId);
        return viewMapper.health(evaluator.toHealth(clusterId, topology.nodes()));
    }

    /**
     * A live probe of the first manageable node, with the recorded management-write
     * evidence overlaid (ADR-0049 D5). The probe itself never writes, so without
     * that overlay the write capability could only ever be unknown.
     */
    @Transactional(readOnly = true)
    public CapabilitiesView capabilities(UUID clusterId) {
        return viewMapper.capabilities(brokerCapabilities(clusterId));
    }

    /** The same assessment as {@link #capabilities}, before it becomes a DTO. */
    @Transactional(readOnly = true)
    public BrokerCapabilities brokerCapabilities(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        requireCluster(clusterId);
        BrokerNodeEntity manageable = manageableNode(clusterId);
        JolokiaBrokerClient client = connections.forCluster(clusterId, manageable.getJolokiaUrl());
        return capabilityProbe.probe(
                client, coreSubscriptions.verdictFor(clusterId), capabilityLedger.managementWrite(clusterId));
    }

    /**
     * The node to assess capabilities against: a live one for preference, any
     * manageable one otherwise. A passive backup answers management reads but
     * registers no acceptor and no address MBeans, so probing one reports "CORE
     * acceptor not found" and "activemq.notifications address not found" about a
     * broker where both are present. Ordering by name alone made that the normal
     * outcome for any cluster whose backup sorts first.
     */
    private BrokerNodeEntity manageableNode(UUID clusterId) {
        return chooseManageable(nodes.findByClusterIdOrderByNameAsc(clusterId))
                .orElseThrow(() -> new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNREACHABLE,
                        "This cluster has no node with a management URL yet."));
    }

    static Optional<BrokerNodeEntity> chooseManageable(List<BrokerNodeEntity> nodes) {
        List<BrokerNodeEntity> manageable =
                nodes.stream().filter(n -> n.getJolokiaUrl() != null).toList();
        return manageable.stream()
                .filter(n -> Boolean.TRUE.equals(n.getActive()) && n.getLastError() == null)
                .findFirst()
                .or(() -> manageable.stream().findFirst());
    }

    // ---- mutations ------------------------------------------------------------

    @Transactional
    public Attempt<TopologyView> rediscover(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_WRITE);
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
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_WRITE);
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
        clusterAccess.requireCluster(clusterId, Permissions.SETTINGS_WRITE);
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
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_WRITE);
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
        environmentIndex.invalidate();
        audit.succeed(event, 1);
    }

    /** Ordinary, editable, unrouted rows an operator can silence or route (design.md decision 8) — not an unconditional check. */
    private void seedBuiltinAlertRules(UUID clusterId) {
        alertRules.save(AlertRuleEntity.state(clusterId, "Split-brain", "SPLIT_BRAIN", 0, "CRITICAL"));
        // Warning, not critical: a wrong clock does not stop the brokers, but it does
        // make Studio's own deadlines and latencies wrong, so it must not be silent
        // (ADR-0053). An ordinary rule like any other — editable and silenceable.
        alertRules.save(AlertRuleEntity.state(clusterId, "Clock skew", "CLOCK_SKEW", 0, "WARNING"));
        alertRules.save(AlertRuleEntity.state(clusterId, "Node down", "NODE_DOWN", 30, "CRITICAL"));
        alertRules.save(AlertRuleEntity.state(clusterId, "Replication behind", "REPLICATION_BEHIND", 120, "WARNING"));
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
