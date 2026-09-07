package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.ConnectionOperations;
import io.github.sudoitir.artemisstudio.broker.ConnectionOperations.ConnectionSnapshot;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.security.ActorResolver;
import io.github.sudoitir.artemisstudio.service.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.artemisstudio.service.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.artemisstudio.sse.SseHub;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Closing a connection, a session, or an address's consumers (ADR-0057).
 *
 * <p>Three properties shape everything here, and all three follow from the fact
 * that a connection identifier is issued by one node and may be gone by the time
 * an operator clicks:
 *
 * <ul>
 *   <li><b>A close by id names a node.</b> Every other mutating operation in
 *       Studio takes a cluster; this one does not, and the inconsistency is
 *       deliberate (D1). Trying an id on every node would either mean nothing or
 *       collide with an unrelated connection. Only the address-scoped close is a
 *       coherent cluster-wide intent, and it fans out.
 *   <li><b>A vanished target is a success</b> (D2). The requested state — that
 *       connection is not open — holds. The outcome still distinguishes
 *       {@code APPLIED} from {@code ALREADY} so the audit record is accurate.
 *   <li><b>The target is re-read immediately before the close</b> (D3), never
 *       trusted from the row a client sent. That read is the only chance to
 *       capture who is being disconnected: afterwards the id resolves to nothing,
 *       and an audit row naming only the id would be useless.
 *   </ul>
 *
 * <p>Nothing is retried. The operation is not idempotent and an id can be reissued
 * between the read and the close, so a retry may act on a different connection.
 *
 * <p>The result is a {@link LifecycleOutcome} — the same per-node shape change 01
 * introduced — with exactly one entry for a node-scoped close. That keeps one
 * outcome vocabulary and one UI component across every cluster mutation.
 */
@Service
@RequiredArgsConstructor
public class ConnectionControlService {

    private final BrokerNodeRepository brokerNodes;
    private final BrokerConnections connections;
    private final ConnectionOperations ops;
    private final NodeCallLimiter limiter;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final SettingsService settings;
    private final SseHub sseHub;
    private final ClusterAccessGuard clusterAccess;

    /**
     * One close's result: what was found immediately before it, and what each node
     * reported.
     *
     * @param target the pre-close snapshot, or {@code null} for an address-scoped
     *     close (which has no single application to name) and for a target that was
     *     already gone
     */
    public record CloseResult(
            ConnectionCloseKind kind, String subject, ConnectionSnapshot target, LifecycleOutcome outcome) {}

    /**
     * What one audit row carries. {@code audit_event.params} is immutable after
     * insert — by design — and the identity is only knowable after the pre-close
     * read, so it rides in the outcome detail beside the per-node result (D3).
     */
    private record CloseDetail(ConnectionSnapshot target, List<NodeOutcome> nodes) {}

    // ---- node-scoped closes ----------------------------------------------

    @Transactional(noRollbackFor = {IllegalArgumentException.class})
    public Attempt<CloseResult> closeConnection(UUID clusterId, UUID nodeId, String connectionId, boolean dryRun) {
        return byId(clusterId, nodeId, ConnectionCloseKind.CONNECTION, connectionId, dryRun);
    }

    @Transactional(noRollbackFor = {IllegalArgumentException.class})
    public Attempt<CloseResult> closeSession(UUID clusterId, UUID nodeId, String sessionId, boolean dryRun) {
        return byId(clusterId, nodeId, ConnectionCloseKind.SESSION, sessionId, dryRun);
    }

    @Transactional(noRollbackFor = {IllegalArgumentException.class})
    public Attempt<CloseResult> closeConsumerConnection(
            UUID clusterId, UUID nodeId, String consumerId, boolean dryRun) {
        return byId(clusterId, nodeId, ConnectionCloseKind.CONSUMER, consumerId, dryRun);
    }

    /**
     * The one path for every close that names an id on a node.
     *
     * <p>Resolution walks outwards from whatever id the view had — a consumer knows
     * its session, a session knows its connection — because the broker's rows carry
     * the link and the client must not be the one to supply it.
     */
    private Attempt<CloseResult> byId(
            UUID clusterId, UUID nodeId, ConnectionCloseKind kind, String id, boolean dryRun) {
        clusterAccess.requireCluster(clusterId, kind.permission());
        String subject = requireId(kind, id);
        BrokerNodeEntity node = manageableNode(clusterId, nodeId);

        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                kind.auditName(),
                kind.targetType(),
                subject,
                clusterId,
                node.getId(),
                Map.of("id", subject),
                dryRun);

        try {
            JolokiaBrokerClient client = clientFor(clusterId, node);
            Resolved resolved = resolve(client, kind, subject);

            // Already gone. The requested state holds, so this is a success on every
            // path — including the audit row, which records what was asked for and
            // that there was nothing left to close.
            if (resolved == null) {
                // ALREADY on a preview too. A dry run of a target that has already
                // gone has nothing it "would apply" to, and reporting one would put
                // "already gone" and "would apply" on the screen together.
                LifecycleOutcome outcome = single(node, dryRun, NodeStatus.ALREADY, null);
                audit.finish(event, false, 0, null, new CloseDetail(null, outcome.nodes()));
                return new Attempt.Ok<>(new CloseResult(kind, subject, null, outcome));
            }

            ConnectionSnapshot target = resolved.target();

            if (dryRun) {
                LifecycleOutcome outcome = single(node, true, NodeStatus.WOULD_APPLY, target.messagesInTransit());
                audit.finish(event, false, 0, null, new CloseDetail(target, outcome.nodes()));
                return new Attempt.Ok<>(new CloseResult(kind, subject, target, outcome));
            }

            boolean closed = kind == ConnectionCloseKind.SESSION
                    ? ops.closeSession(
                            client, client.resolveBrokerObjectName(), resolved.connectionId(), resolved.sessionId())
                    : ops.closeConnection(client, client.resolveBrokerObjectName(), resolved.connectionId());

            LifecycleOutcome outcome = single(
                    node,
                    false,
                    closed ? NodeStatus.APPLIED : NodeStatus.ALREADY,
                    closed ? target.messagesInTransit() : null);
            audit.finish(event, false, closed ? 1 : 0, null, new CloseDetail(target, outcome.nodes()));
            publishAfterCommit(clusterId);
            return new Attempt.Ok<>(new CloseResult(kind, subject, target, outcome));
        } catch (BrokerConnectionException e) {
            audit.fail(event, e.getMessage());
            return new Attempt.Failed<>(e.kind(), e.getMessage());
        }
    }

    /** What a close by id ends up acting on, once the broker's own rows have been walked. */
    private record Resolved(String connectionId, String sessionId, ConnectionSnapshot target) {}

    private Resolved resolve(JolokiaBrokerClient client, ConnectionCloseKind kind, String id) {
        switch (kind) {
            case CONNECTION -> {
                ConnectionSnapshot target = ops.readConnection(client, id);
                return target == null ? null : new Resolved(id, null, target);
            }
            case SESSION -> {
                ConnectionSnapshot target = ops.readSession(client, id);
                if (target == null || target.connectionId() == null) {
                    return null;
                }
                return new Resolved(target.connectionId(), id, target);
            }
            case CONSUMER -> {
                // A consumer row names its session but not its connection, so the
                // connection is found through the session rather than guessed from the
                // remote address. Closing a consumer closes the connection behind it —
                // which is the intervention the slow-consumer finding implies.
                String sessionId = ops.sessionIdOfConsumer(client, id);
                if (sessionId == null) {
                    return null;
                }
                String connectionId = ops.connectionIdOfSession(client, sessionId);
                if (connectionId == null) {
                    return null;
                }
                ConnectionSnapshot target = ops.readConnection(client, connectionId);
                return target == null ? null : new Resolved(connectionId, sessionId, target);
            }
            default -> throw new IllegalStateException("not a by-id close: " + kind);
        }
    }

    // ---- address-scoped close --------------------------------------------

    /**
     * Close every consumer connection bound to an address, on every live node.
     *
     * <p>The one cluster-wide close, and the only one with an unbounded blast
     * radius — so it is estimated per node first, checked against the ADR-0022 bulk
     * cap, and refused above it without an explicit override (D6). It is not exempt
     * because it is "just a disconnect": each consumer it closes returns its
     * in-flight messages to a queue.
     */
    @Transactional(noRollbackFor = {BulkCapExceededException.class, IllegalArgumentException.class})
    public Attempt<CloseResult> closeAddressConsumers(
            UUID clusterId, String address, boolean dryRun, boolean override) {
        ConnectionCloseKind kind = ConnectionCloseKind.ADDRESS_CONSUMERS;
        clusterAccess.requireCluster(clusterId, kind.permission());
        String subject = requireId(kind, address);
        List<Target> targets = liveTargets(clusterId);
        long cap = settings.bulkCap();

        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                kind.auditName(),
                kind.targetType(),
                subject,
                clusterId,
                null,
                Map.of("address", subject),
                dryRun);

        // Estimated across every live node before anything is closed, so the cap sees
        // the whole blast radius and a preview reports a real per-node number.
        Map<UUID, Long> estimates = new LinkedHashMap<>();
        for (Target t : targets) {
            if (!t.live()) {
                continue;
            }
            try {
                estimates.put(t.node().getId(), ops.countConsumersForAddress(clientFor(clusterId, t.node()), subject));
            } catch (BrokerConnectionException e) {
                estimates.put(t.node().getId(), null);
            }
        }
        long total = estimates.values().stream()
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();

        // A node that could not be counted makes the total a floor, not a figure.
        // Summing only the nodes that answered would let the cap pass on a number
        // known to be incomplete — in exactly the situation where the operator most
        // needs to be stopped. An unknown estimate therefore takes the same explicit
        // override an over-cap one does.
        boolean incomplete = estimates.containsValue(null);
        boolean overCap = total > cap || incomplete;

        List<NodeOutcome> outcomes = new ArrayList<>();
        if (dryRun) {
            for (Target t : targets) {
                outcomes.add(
                        t.live()
                                ? new NodeOutcome(
                                        t.node().getId(),
                                        t.node().getName(),
                                        NodeStatus.WOULD_APPLY,
                                        estimates.get(t.node().getId()),
                                        // Stated, never omitted: an absent count reads as
                                        // zero, which is the wrong thing to infer here.
                                        estimates.get(t.node().getId()) == null
                                                ? "This node did not answer, so its consumer count is unknown."
                                                : null)
                                : NodeOutcome.skipped(t.node().getId(), t.node().getName()));
            }
            LifecycleOutcome outcome = new LifecycleOutcome(true, cap, overCap, outcomes);
            audit.finish(event, false, total, null, new CloseDetail(null, outcomes));
            return new Attempt.Ok<>(new CloseResult(kind, subject, null, outcome));
        }

        if (overCap && !override) {
            String reason = incomplete
                    ? "At least one node did not report its consumer count, so the blast radius is not known (" + total
                            + " counted, cap " + cap + ")."
                    : "Over the safety cap (" + total + " > " + cap + ").";
            audit.finish(event, true, total, reason, new CloseDetail(null, outcomes));
            throw new BulkCapExceededException(total, cap);
        }

        for (Target t : targets) {
            if (!t.live()) {
                outcomes.add(NodeOutcome.skipped(t.node().getId(), t.node().getName()));
                continue;
            }
            UUID id = t.node().getId();
            String name = t.node().getName();
            try {
                JolokiaBrokerClient client = clientFor(clusterId, t.node());
                boolean closed =
                        ops.closeConsumerConnectionsForAddress(client, client.resolveBrokerObjectName(), subject);
                outcomes.add(new NodeOutcome(
                        id,
                        name,
                        closed ? NodeStatus.APPLIED : NodeStatus.ALREADY,
                        closed ? estimates.get(id) : 0L,
                        null));
            } catch (BrokerConnectionException e) {
                // One node's failure does not abort the fan-out, and nothing is rolled
                // back: a connection that has been closed cannot be reopened by Studio.
                outcomes.add(NodeOutcome.failed(id, name, e.getMessage()));
            }
        }

        LifecycleOutcome outcome = new LifecycleOutcome(false, cap, overCap, outcomes);
        audit.finish(
                event,
                outcome.anyFailed(),
                outcome.totalAffected(),
                failureSummary(outcomes),
                new CloseDetail(null, outcomes));
        publishAfterCommit(clusterId);
        return new Attempt.Ok<>(new CloseResult(kind, subject, null, outcome));
    }

    // ---- plumbing --------------------------------------------------------

    private record Target(BrokerNodeEntity node, boolean live) {}

    /**
     * A node-scoped close's outcome, in the same per-node shape a cluster-wide one
     * uses — with one entry. The cap fields are zero and false because this kind is
     * not capped: it closes exactly one connection.
     */
    private static LifecycleOutcome single(BrokerNodeEntity node, boolean dryRun, NodeStatus status, Long affected) {
        return new LifecycleOutcome(
                dryRun, 0, false, List.of(new NodeOutcome(node.getId(), node.getName(), status, affected, null)));
    }

    private static String requireId(ConnectionCloseKind kind, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    kind == ConnectionCloseKind.ADDRESS_CONSUMERS ? "An address is required." : "An id is required.");
        }
        return id.trim();
    }

    /** The node an id was issued by. A node with no management URL cannot be asked. */
    private BrokerNodeEntity manageableNode(UUID clusterId, UUID nodeId) {
        return brokerNodes.findByClusterIdOrderByNameAsc(clusterId).stream()
                .filter(n -> n.getId().equals(nodeId) && n.getJolokiaUrl() != null)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("node", nodeId));
    }

    /**
     * One target per logical node, live state from the polled {@code Active}
     * attribute rather than configuration (non-negotiable #4) — the same rule the
     * lifecycle fan-out follows.
     */
    private List<Target> liveTargets(UUID clusterId) {
        Map<String, List<BrokerNodeEntity>> logical = new LinkedHashMap<>();
        for (BrokerNodeEntity node : brokerNodes.findByClusterIdOrderByNameAsc(clusterId)) {
            if (node.getJolokiaUrl() == null) {
                continue;
            }
            String key = node.getArtemisNodeId() != null ? node.getArtemisNodeId() : "id:" + node.getId();
            logical.computeIfAbsent(key, k -> new ArrayList<>()).add(node);
        }
        List<Target> targets = new ArrayList<>();
        for (List<BrokerNodeEntity> group : logical.values()) {
            group.stream()
                    .filter(n -> Boolean.TRUE.equals(n.getActive()))
                    .findFirst()
                    .ifPresentOrElse(
                            live -> targets.add(new Target(live, true)),
                            () -> targets.add(new Target(group.get(0), false)));
        }
        if (targets.isEmpty()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    "This cluster has no node with a management URL, so nothing can be closed on it.");
        }
        return targets;
    }

    private static String failureSummary(List<NodeOutcome> outcomes) {
        List<String> failed = outcomes.stream()
                .filter(n -> n.status() == NodeStatus.FAILED)
                .map(n -> n.nodeName() + ": " + n.error())
                .sorted(Comparator.naturalOrder())
                .toList();
        return failed.isEmpty() ? null : String.join(" | ", failed);
    }

    private JolokiaBrokerClient clientFor(UUID clusterId, BrokerNodeEntity node) {
        try {
            limiter.acquire(node.getId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE, "Timed out waiting for a per-node call permit.");
        }
        return connections.forCluster(clusterId, node.getJolokiaUrl());
    }

    /**
     * A close changes the connection, session and consumer views at once, so all
     * three topics are nudged — a view left showing a connection that is gone is
     * exactly the staleness this feature exists to act on.
     */
    private void publishAfterCommit(UUID clusterId) {
        Runnable publish = () -> {
            sseHub.publish(clusterId, "connections");
            sseHub.publish(clusterId, "sessions");
            sseHub.publish(clusterId, "consumers");
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
