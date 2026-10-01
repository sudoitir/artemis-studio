package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaSignal;
import io.github.sudoitir.artemisstudio.kernel.replica.StudioBus;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.SecretVault;
import io.github.sudoitir.artemisstudio.kernel.security.SettingsPermissions;
import io.github.sudoitir.artemisstudio.platform.broker.Attempt;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerCapabilities;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerClientFactory;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionSettings;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerVersion;
import io.github.sudoitir.artemisstudio.platform.broker.CapabilityProbe;
import io.github.sudoitir.artemisstudio.platform.broker.CoreConnectionSettings;
import io.github.sudoitir.artemisstudio.platform.broker.CoreSubscriptionCheck;
import io.github.sudoitir.artemisstudio.platform.broker.CoreSubscriptionManager;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaResponse;
import io.github.sudoitir.artemisstudio.platform.broker.NodeEndpoint;
import io.github.sudoitir.artemisstudio.platform.broker.SubscriptionVerdict;
import io.github.sudoitir.artemisstudio.platform.broker.VersionGate;
import io.github.sudoitir.artemisstudio.platform.clusters.TopologyDiscovery.ProbedSeed;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerCredentialEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerCredentialRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerIdentityClaims;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerTlsEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerTlsRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterRequests.NodeOverrideRequest;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.CapabilitiesView;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.ClusterSummary;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.HealthView;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.NodeEndpointView;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.RegisterPreview;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.TopologyView;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
    private static final String REGISTER_CLUSTER = "REGISTER_CLUSTER";
    private static final String CLUSTER = "CLUSTER";
    private static final UUID UNBOUND = new UUID(0L, 0L);

    private final ClusterRepository clusters;
    private final BrokerNodeRepository nodes;
    private final BrokerCredentialRepository credentials;
    private final BrokerTlsRepository tlsRepository;
    private final BrokerIdentityClaims identityClaims;

    private final BrokerClientFactory clientFactory;
    private final BrokerConnections connections;
    private final List<RegistrationCheckContributor> checkContributors;
    private final CapabilityProbe capabilityProbe;
    private final CapabilityLedger capabilityLedger;
    private final TopologyDiscovery topologyDiscovery;
    private final HaStateEvaluator evaluator;
    private final CoreSubscriptionManager coreSubscriptions;
    private final CoreSubscriptionCheck coreSubscriptionCheck;
    private final StudioBus bus;
    private final SecretVault vault;
    private final AuditService audit;
    private final ApplicationEventPublisher eventPublisher;
    private final io.github.sudoitir.artemisstudio.kernel.security.ActorResolver actorResolver;
    private final ClusterEnvironmentIndex environmentIndex;
    private final ClusterAccessGuard clusterAccess;
    private final PermissionResolver permissions;

    private final BrokerNodeMapper nodeMapper;
    private final ClusterViewMapper viewMapper;

    private record Probe(String url, JolokiaBrokerClient client, String version, BrokerConnectionException error) {
        boolean ok() {
            return error == null;
        }

        ProbedSeed asSeed() {
            return new ProbedSeed(url, client);
        }
    }

    // ---- connection check (?dryRun=true) -------------------------------------

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.platform.clusters.ClusterPermissions).CLUSTER_WRITE)")
    @Transactional
    public Attempt<RegisterPreview> checkConnection(RegisterClusterRequest request) {
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                REGISTER_CLUSTER,
                CLUSTER,
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
        BrokerConnectionException tooOld = belowMinimum(reachable);
        if (tooOld != null) {
            return failed(event, tooOld);
        }

        TopologyDiscovery.Survey survey =
                topologyDiscovery.survey(reachable.stream().map(Probe::asSeed).toList());
        refuseIfRegistered(survey.identity(), () -> event);
        ClusterTopology preview = topologyDiscovery.preview(survey);

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

        Map<String, Object> contributions = new TreeMap<>();
        for (RegistrationCheckContributor contributor : checkContributors) {
            contributions.put(
                    contributor.featureId(),
                    contributor.contribute(capabilities, reachable.get(0).client()));
        }

        audit.succeed(event, nodeCount);
        return new Attempt.Ok<>(new RegisterPreview(
                viewMapper.capabilities(capabilities, VersionGate.assessAll(endpoints(preview))),
                reachable.size(),
                nodeCount,
                viewMapper.topology(preview),
                contributions));
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

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.platform.clusters.ClusterPermissions).CLUSTER_WRITE)")
    @Transactional
    public Attempt<ClusterDetail> register(RegisterClusterRequest request) {
        List<Probe> probes = connectAll(request);
        List<Probe> reachable = probes.stream().filter(Probe::ok).toList();
        if (reachable.isEmpty()) {
            AuditEvent event = audit.begin(
                    actorResolver.resolve(), REGISTER_CLUSTER, CLUSTER, request.name(), null, null, Map.of(), false);
            return failed(event, probes.get(0).error());
        }
        BrokerConnectionException tooOld = belowMinimum(reachable);
        if (tooOld != null) {
            AuditEvent event = audit.begin(
                    actorResolver.resolve(),
                    "REGISTER_CLUSTER",
                    "CLUSTER",
                    request.name(),
                    null,
                    null,
                    Map.of("seedUrls", request.seedUrls()),
                    false);
            return failed(event, tooOld);
        }

        TopologyDiscovery.Survey survey =
                topologyDiscovery.survey(reachable.stream().map(Probe::asSeed).toList());
        ClusterIdentity identity = survey.identity();
        refuseIfRegistered(identity, () -> refusedAttempt(request));

        ClusterEntity cluster = clusters.saveAndFlush(new ClusterEntity(
                request.name() != null
                        ? request.name()
                        : hostOf(request.seedUrls().get(0)),
                request.description(),
                null));
        UUID clusterId = cluster.getId();

        // The check above sees committed clusters only. The claim settles two registrations of the same
        // brokers running at once: it waits for the other to commit, then finds the brokers taken, and
        // throwing rolls this cluster back (ADR-0167).
        Optional<UUID> holder = identityClaims.claim(clusterId, identity.claims());
        if (holder.isPresent()) {
            ClusterIdentity.Overlap overlap = registeredOverlap(identity)
                    .orElseGet(() -> new ClusterIdentity.Overlap(
                            holder.get(),
                            clusters.findById(holder.get())
                                    .map(ClusterEntity::getName)
                                    .orElse(""),
                            List.of()));
            throw refusal(overlap, refusedAttempt(request));
        }

        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                REGISTER_CLUSTER,
                CLUSTER,
                cluster.getName(),
                clusterId,
                null,
                Map.of("seedUrls", request.seedUrls()),
                false);

        if (request.hasCredentials()) {
            credentials.save(new BrokerCredentialEntity(
                    clusterId,
                    JOLOKIA_BASIC,
                    request.credentials().username(),
                    vault.seal(
                            SecretVault.aad(clusterId, JOLOKIA_BASIC),
                            request.credentials().password())));
        }
        if (request.hasCoreCredentials()) {
            // A CORE row is a genuinely separate sealed secret — AAD is clusterId|CORE
            // (ADR-0026). When absent, coreSettingsFor falls back to the Jolokia credential.
            credentials.save(new BrokerCredentialEntity(
                    clusterId,
                    CORE,
                    request.coreCredentials().username(),
                    vault.seal(
                            SecretVault.aad(clusterId, CORE),
                            request.coreCredentials().password())));
        }
        if (request.tlsBundle() != null) {
            tlsRepository.save(new BrokerTlsEntity(clusterId, request.tlsBundle(), null, true));
        }

        ClusterTopology topology = topologyDiscovery.discover(clusterId, survey);
        BrokerCapabilities capabilities = capabilityProbe.probe(
                reachable.get(0).client(),
                coreSubscriptions.verdictFor(clusterId),
                capabilityLedger.managementWrite(clusterId));
        eventPublisher.publishEvent(new ClusterRegistered(clusterId));
        environmentIndex.invalidate();

        audit.succeed(event, endpointCount(topology));
        return new Attempt.Ok<>(new ClusterDetail(
                clusterId,
                cluster.getName(),
                cluster.getDescription(),
                viewMapper.topology(topology),
                viewMapper.capabilities(capabilities, VersionGate.assessAll(endpoints(topology))),
                viewMapper.health(evaluator.toHealth(clusterId, topology.nodes())),
                cluster.getEnvironmentId()));
    }

    // ---- one registration per set of brokers (ADR-0167) ---------------------

    /** Refuses brokers a registered cluster already holds, recording the refusal on the attempt's audit row. */
    private void refuseIfRegistered(ClusterIdentity identity, Supplier<AuditEvent> attempt) {
        Optional<ClusterIdentity.Overlap> overlap = registeredOverlap(identity);
        if (overlap.isPresent()) {
            throw refusal(overlap.get(), attempt.get());
        }
    }

    /** The registered cluster holding any of these brokers, by NodeID or by a management URL's normal form. */
    private Optional<ClusterIdentity.Overlap> registeredOverlap(ClusterIdentity identity) {
        List<BrokerNodeEntity> candidates = new ArrayList<>(nodes.findByJolokiaUrlIsNotNull());
        if (!identity.nodeIds().isEmpty()) {
            candidates.addAll(nodes.findByArtemisNodeIdIn(identity.nodeIds()));
        }
        Map<UUID, String> names = new HashMap<>();
        clusters.findAllById(candidates.stream()
                        .map(BrokerNodeEntity::getClusterId)
                        .distinct()
                        .toList())
                .forEach(c -> names.put(c.getId(), c.getName()));
        return identity.overlapWith(candidates, names);
    }

    /**
     * The refusal for an overlap. The audit row records the cluster it names; the caller is told which
     * cluster only when they may read it (the authorization spec keeps a cluster hidden from anyone with
     * no grant on it).
     */
    private ClusterAlreadyRegisteredException refusal(ClusterIdentity.Overlap overlap, AuditEvent attempt) {
        ClusterAlreadyRegisteredException named =
                new ClusterAlreadyRegisteredException(overlap.clusterId(), overlap.clusterName(), overlap.nodes());
        audit.fail(attempt, named.getMessage());
        return permissions.can(overlap.clusterId(), Permissions.CLUSTER_READ)
                ? named
                : ClusterAlreadyRegisteredException.hidden();
    }

    /** The audit row of a registration refused before it created a cluster. */
    private AuditEvent refusedAttempt(RegisterClusterRequest request) {
        return audit.begin(
                actorResolver.resolve(),
                REGISTER_CLUSTER,
                CLUSTER,
                request.name(),
                null,
                null,
                Map.of("seedUrls", request.seedUrls()),
                false);
    }

    // ---- reads ------------------------------------------------------------

    @PostFilter(
            "@perm.can(filterObject.id(), T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).CLUSTER_READ)")
    @Transactional(readOnly = true)
    public List<ClusterSummary> list() {
        List<ClusterSummary> out = new ArrayList<>();
        for (ClusterEntity c : clusters.findAllByOrderByNameAsc()) {
            List<BrokerNodeEntity> rows = nodes.findByClusterIdOrderByNameAsc(c.getId());
            var logical = evaluator.toLogicalNodes(nodeMapper.toEndpoints(rows), SplitBrainStatus.byNodeId(rows));
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
                viewMapper.capabilities(assessCapabilities(clusterId), VersionGate.assessAll(endpoints(topology))),
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
        return viewMapper.capabilities(
                assessCapabilities(clusterId),
                VersionGate.assessAll(endpoints(topologyDiscovery.currentTopology(clusterId))));
    }

    /** The same assessment as {@link #capabilities}, before it becomes a DTO. */
    @Transactional(readOnly = true)
    public BrokerCapabilities brokerCapabilities(UUID clusterId) {
        return assessCapabilities(clusterId);
    }

    private BrokerCapabilities assessCapabilities(UUID clusterId) {
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

    /**
     * Re-run discovery from every manageable node of a cluster, so a broker that joined
     * after registration appears on its own (ADR-0004, ADR-0119). Called by the scrape
     * scheduler's discovery tier: a system operation, with no permission check and no
     * audit event per tick, like the tiers' own writes. Discovery reads each seed on its
     * own, so a node that does not answer is skipped this round (tier A records its
     * error) and the others still count.
     */
    public void rediscover(UUID clusterId) {
        List<ProbedSeed> seeds = new ArrayList<>();
        for (BrokerNodeEntity node : nodes.findByClusterIdOrderByNameAsc(clusterId)) {
            if (node.getJolokiaUrl() == null) {
                continue;
            }
            seeds.add(new ProbedSeed(node.getJolokiaUrl(), connections.forCluster(clusterId, node.getJolokiaUrl())));
        }
        if (!seeds.isEmpty()) {
            topologyDiscovery.discover(clusterId, seeds);
        }
    }

    @Transactional
    public Attempt<NodeEndpointView> overrideNodeUrl(UUID clusterId, UUID nodeId, NodeOverrideRequest request) {
        clusterAccess.requireCluster(clusterId, ClusterPermissions.CLUSTER_WRITE);
        requireCluster(clusterId);
        BrokerNodeEntity node = nodes.findById(nodeId)
                .filter(n -> n.getClusterId().equals(clusterId))
                .orElseThrow(() -> new NotFoundException("Node", nodeId));
        if (request.hasJolokiaUrl()
                && nodes.existsByClusterIdAndJolokiaUrlAndIdNot(clusterId, request.jolokiaUrl(), nodeId)) {
            throw new ConflictException(
                    "duplicate-node-url", "Another node of this cluster already uses " + request.jolokiaUrl() + ".");
        }

        Map<String, Object> params = new HashMap<>();
        if (request.hasJolokiaUrl()) {
            params.put("jolokiaUrl", request.jolokiaUrl());
        }
        if (request.hasCoreUrl()) {
            params.put("coreUrl", request.coreUrl());
        }
        AuditEvent event = audit.begin(
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
        clusterAccess.requireCluster(clusterId, SettingsPermissions.SETTINGS_WRITE);
        ClusterEntity cluster = requireCluster(clusterId);
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                "ROTATE_CREDENTIALS",
                CLUSTER,
                cluster.getName(),
                clusterId,
                null,
                Map.of("username", username, "kind", kind),
                false);

        byte[] sealed = vault.seal(SecretVault.aad(clusterId, kind), password);
        credentials
                .findByClusterIdAndKind(clusterId, kind)
                .ifPresentOrElse(
                        existing -> existing.replaceSecret(username, sealed),
                        () -> credentials.save(new BrokerCredentialEntity(clusterId, kind, username, sealed)));

        audit.succeed(event, 1);
    }

    @Transactional
    public void delete(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, ClusterPermissions.CLUSTER_WRITE);
        ClusterEntity cluster = requireCluster(clusterId);
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                "DELETE_CLUSTER",
                CLUSTER,
                cluster.getName(),
                clusterId,
                null,
                Map.of(),
                false);
        clusters.delete(cluster);
        bus.publish(new ReplicaSignal("cluster-deleted", clusterId.toString()));
        environmentIndex.invalidate();
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
                probes.add(new Probe(url, client, brokerVersion(client), null));
            } catch (BrokerConnectionException e) {
                probes.add(new Probe(url, null, null, e));
            }
        }
        return probes;
    }

    /** The broker's {@code Version} attribute, or null when the read is refused; the range check then has nothing to refuse on. */
    private static String brokerVersion(JolokiaBrokerClient client) {
        JolokiaResponse response = client.readBrokerAttributes("Version");
        return response.ok() && response.attribute("Version") != null
                ? response.attribute("Version").asString()
                : null;
    }

    /** A refusal naming the minimum when any reachable seed runs an older release (ADR-0142), else null. */
    private static BrokerConnectionException belowMinimum(List<Probe> reachable) {
        return reachable.stream()
                .filter(p -> BrokerVersion.support(p.version()) == BrokerVersion.Support.BELOW_MINIMUM)
                .findFirst()
                .map(p -> new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNSUPPORTED_VERSION,
                        "The broker at " + p.url() + " runs Artemis " + p.version() + ". Studio supports Artemis "
                                + BrokerVersion.MINIMUM + " and later; upgrade the broker to register it."))
                .orElse(null);
    }

    private static List<NodeEndpoint> endpoints(ClusterTopology topology) {
        return topology.nodes().stream().flatMap(n -> n.endpoints().stream()).toList();
    }

    private <T> Attempt<T> failed(AuditEvent event, BrokerConnectionException error) {
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
        } catch (RuntimeException _) {
            return url;
        }
    }
}
