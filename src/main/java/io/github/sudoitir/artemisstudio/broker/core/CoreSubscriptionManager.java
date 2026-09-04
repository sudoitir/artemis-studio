package io.github.sudoitir.artemisstudio.broker.core;

import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.domain.topology.NodeEndpoint;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Reconciles Core {@code activemq.notifications} subscriptions against the live
 * topology (ADR-0026, D2): one subscription per serving node with a dialable
 * Core URL. Called at the end of every tier-A scrape, on the scheduler's
 * virtual-thread pool, never inside a transaction.
 *
 * <p>{@link #verdictFor(UUID)} is the cached answer {@code CapabilityProbe} reads
 * — it never opens a connection (D5).
 */
@Component
@Slf4j
public class CoreSubscriptionManager {

    private static final Duration RETRY_INITIAL = Duration.ofSeconds(1);
    private static final Duration RETRY_MAX = Duration.ofMinutes(5);

    private final BrokerConnections connections;
    private final CoreConnectionFactory connectionFactory;
    private final NotificationMapper mapper;
    private final ObjectProvider<BrokerEventSink> sink;

    /** nodeId -> its running client. */
    private final Map<UUID, CoreEventClient> active = new ConcurrentHashMap<>();
    /** nodeId -> backoff after a failed start. */
    private final Map<UUID, Backoff> retry = new ConcurrentHashMap<>();
    /** nodeId -> its last failure, kept for the verdict after the client is gone. */
    private final Map<UUID, CoreEventClient.State.Failed> lastFailure = new ConcurrentHashMap<>();
    /** clusterId -> its nodeIds, so a verdict can be built per cluster. */
    private final Map<UUID, Set<UUID>> nodesByCluster = new ConcurrentHashMap<>();
    /** nodeId -> its clusterId, so reconcile only prunes its own cluster's nodes. */
    private final Map<UUID, UUID> clusterByNode = new ConcurrentHashMap<>();

    public CoreSubscriptionManager(
            BrokerConnections connections,
            CoreConnectionFactory connectionFactory,
            NotificationMapper mapper,
            ObjectProvider<BrokerEventSink> sink) {
        this.connections = connections;
        this.connectionFactory = connectionFactory;
        this.mapper = mapper;
        this.sink = sink;
    }

    /** Reconcile subscriptions for one cluster against its current endpoints. */
    public void reconcile(UUID clusterId, List<NodeEndpoint> endpoints) {
        Set<UUID> desired = endpoints.stream()
                .filter(NodeEndpoint::live)
                .filter(e -> CoreUrl.dialable(e.coreUrl()) != null)
                .map(NodeEndpoint::id)
                .collect(Collectors.toSet());

        Set<UUID> clusterNodes = endpoints.stream().map(NodeEndpoint::id).collect(Collectors.toSet());
        nodesByCluster.put(clusterId, clusterNodes);
        clusterNodes.forEach(id -> clusterByNode.put(id, clusterId));

        // Stop this cluster's subscriptions that are no longer wanted (node gone,
        // failed over, or Core URL cleared).
        for (UUID nodeId : Set.copyOf(active.keySet())) {
            if (clusterId.equals(clusterByNode.get(nodeId)) && !desired.contains(nodeId)) {
                stop(nodeId);
            }
        }

        Map<UUID, NodeEndpoint> byId = endpoints.stream().collect(Collectors.toMap(NodeEndpoint::id, e -> e));
        for (UUID nodeId : desired) {
            if (active.containsKey(nodeId)) {
                continue;
            }
            Backoff backoff = retry.get(nodeId);
            if (backoff != null && backoff.notDueYet()) {
                continue;
            }
            start(clusterId, nodeId, byId.get(nodeId));
        }
    }

    private void start(UUID clusterId, UUID nodeId, NodeEndpoint endpoint) {
        try {
            CoreConnectionSettings settings = connections.coreSettingsFor(clusterId);
            ActiveMQConnectionFactory factory = connectionFactory.build(settings, CoreUrl.dialable(endpoint.coreUrl()));
            CoreEventClient client = new CoreEventClient(
                    clusterId, nodeId, factory, mapper, e -> sink.getObject().accept(e));
            client.start();
            active.put(nodeId, client);
            retry.remove(nodeId);
            lastFailure.remove(nodeId);
            log.info("Subscribed to activemq.notifications on node {} ({})", endpoint.name(), clusterId);
        } catch (Exception e) {
            CoreEventClient.Kind kind = CoreEventClient.classify(e);
            lastFailure.put(nodeId, new CoreEventClient.State.Failed(kind, e.getMessage(), Instant.now()));
            retry.computeIfAbsent(nodeId, k -> newBackoff()).recordFailure();
            log.warn("Core subscription for node {} failed ({}): {}", nodeId, kind, e.getMessage());
        }
    }

    private void stop(UUID nodeId) {
        CoreEventClient client = active.remove(nodeId);
        if (client != null) {
            client.close();
            log.info("Closed activemq.notifications subscription on node {}", nodeId);
        }
    }

    private static Backoff newBackoff() {
        return new Backoff(RETRY_INITIAL, RETRY_MAX);
    }

    /** The cached subscription outcome for a cluster (D5) — opens no connection. */
    public SubscriptionVerdict verdictFor(UUID clusterId) {
        Set<UUID> nodeIds = nodesByCluster.get(clusterId);
        if (nodeIds == null || nodeIds.isEmpty()) {
            return new SubscriptionVerdict.NotAttempted();
        }
        List<CoreEventClient> connected = nodeIds.stream()
                .map(active::get)
                .filter(c -> c != null && c.state() instanceof CoreEventClient.State.Connected)
                .toList();
        if (!connected.isEmpty()) {
            Instant since = connected.stream()
                    .map(c -> ((CoreEventClient.State.Connected) c.state()).since())
                    .min(Instant::compareTo)
                    .orElse(Instant.now());
            return new SubscriptionVerdict.Connected(connected.size(), since);
        }
        CoreEventClient.State.Failed failed = nodeIds.stream()
                .map(lastFailure::get)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (failed != null) {
            return new SubscriptionVerdict.Failed(failed.kind(), failed.reason());
        }
        return new SubscriptionVerdict.NotAttempted();
    }

    /** Drop all state for a removed cluster. Wired into {@code ClusterService.delete}. */
    public void forget(UUID clusterId) {
        Set<UUID> nodeIds = nodesByCluster.remove(clusterId);
        if (nodeIds == null) {
            return;
        }
        for (UUID nodeId : nodeIds) {
            stop(nodeId);
            retry.remove(nodeId);
            lastFailure.remove(nodeId);
            clusterByNode.remove(nodeId);
        }
    }

    @PreDestroy
    void shutdown() {
        for (UUID nodeId : Set.copyOf(active.keySet())) {
            stop(nodeId);
        }
    }
}
