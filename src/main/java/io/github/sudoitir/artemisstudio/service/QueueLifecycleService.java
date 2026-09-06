package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.BrokerMBeans;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.ManagementRefusal;
import io.github.sudoitir.artemisstudio.broker.QueueLifecycleOperations;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotRepository;
import io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.security.Actor;
import io.github.sudoitir.artemisstudio.security.ActorResolver;
import io.github.sudoitir.artemisstudio.service.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.artemisstudio.service.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.artemisstudio.sse.SseHub;
import io.github.sudoitir.artemisstudio.web.dto.LifecycleRequests.CreateAddressRequest;
import io.github.sudoitir.artemisstudio.web.dto.LifecycleRequests.CreateQueueRequest;
import io.github.sudoitir.artemisstudio.web.dto.LifecycleRequests.UpdateQueueRequest;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Queue and address lifecycle across a cluster (ADR-0049).
 *
 * <p>A command names a <em>cluster</em>, not a node. Artemis cluster nodes each own
 * their own queues, so "create this queue on the cluster" is N management calls
 * with real partial-failure semantics — and the result is a per-node list, never a
 * boolean (D2). Target nodes come from observed live topology, never from stored
 * configuration (non-negotiable #4); a node that is not live is reported as
 * skipped, because it never received the command.
 *
 * <p>A partial failure is reported, never rolled back (D3). A {@code destroyQueue}
 * that succeeded on one node cannot be undone — the messages are gone — and a
 * compensating create would produce an empty queue with the same name, which is a
 * different state dressed up as the original.
 *
 * <p>One audit row per command carries the whole fan-out (D4), and a command that
 * failed on any node is recorded as failed with the per-node detail attached.
 */
@Service
@RequiredArgsConstructor
public class QueueLifecycleService {

    private final BrokerNodeRepository brokerNodes;
    private final QueueSnapshotRepository queueSnapshots;
    private final BrokerConnections connections;
    private final QueueLifecycleOperations ops;
    private final NodeCallLimiter limiter;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final SettingsService settings;
    private final SseHub sseHub;
    private final ClusterAccessGuard clusterAccess;
    private final CapabilityLedger capabilities;
    private final ObjectMapper mapper;

    /** One node the command will be applied to, with the client already resolved. */
    private record Target(BrokerNodeEntity node, boolean live) {}

    // ---- entry points ----------------------------------------------------

    @Transactional(noRollbackFor = {BulkCapExceededException.class, IllegalArgumentException.class})
    public Attempt<LifecycleOutcome> createQueue(UUID clusterId, CreateQueueRequest req, boolean dryRun) {
        ResolvedQueue asked = new ResolvedQueue(req.name(), req.address(), req.routingType());
        return run(clusterId, LifecycleKind.CREATE_QUEUE, req.name(), params(req), dryRun, false, (client, broker) -> {
            try {
                ops.createQueue(client, broker, queueConfig(req));
                return NodeStatus.APPLIED;
            } catch (ManagementRefusal e) {
                if (e.kind() != ManagementRefusal.Kind.ALREADY) {
                    throw e;
                }
                // The queue already exists here. Whether that is success depends on
                // whether it is the queue that was asked for: same configuration is
                // ALREADY, a different one is a failure that names the difference,
                // because reporting it as success would hide a real mismatch.
                JsonNode existing = asConfigNode(ops.readQueueConfig(client, queueMbean(client, asked)));
                if (matchesRequest(existing, req)) {
                    return NodeStatus.ALREADY;
                }
                throw new ManagementRefusal(
                        ManagementRefusal.Kind.ARGUMENT,
                        "A queue named '" + req.name() + "' already exists here but "
                                + describeDifference(existing, req) + ".");
            }
        });
    }

    @Transactional(noRollbackFor = {BulkCapExceededException.class, IllegalArgumentException.class})
    public Attempt<LifecycleOutcome> updateQueue(
            UUID clusterId, String queueName, UpdateQueueRequest req, boolean dryRun) {
        Map<String, Object> patch = patch(req);
        if (patch.isEmpty()) {
            throw new IllegalArgumentException("An update must change at least one field.");
        }
        ResolvedQueue queue = resolveQueue(clusterId, queueName);
        return run(clusterId, LifecycleKind.UPDATE_QUEUE, queueName, patch, dryRun, false, (client, broker) -> {
            ops.updateQueue(client, broker, queueMbean(client, queue), patch);
            return NodeStatus.APPLIED;
        });
    }

    @Transactional(noRollbackFor = {BulkCapExceededException.class, IllegalArgumentException.class})
    public Attempt<LifecycleOutcome> deleteQueue(UUID clusterId, String queueName, boolean dryRun, boolean override) {
        ResolvedQueue queue = resolveQueue(clusterId, queueName);
        return run(
                clusterId,
                LifecycleKind.DELETE_QUEUE,
                queueName,
                Map.of(),
                dryRun,
                override,
                (client, broker) -> {
                    ops.destroyQueue(client, broker, queueName);
                    return NodeStatus.APPLIED;
                },
                client -> ops.messageCount(client, queueMbean(client, queue)));
    }

    @Transactional(noRollbackFor = {BulkCapExceededException.class, IllegalArgumentException.class})
    public Attempt<LifecycleOutcome> setPaused(UUID clusterId, String queueName, boolean paused, boolean dryRun) {
        ResolvedQueue queue = resolveQueue(clusterId, queueName);
        LifecycleKind kind = paused ? LifecycleKind.PAUSE_QUEUE : LifecycleKind.RESUME_QUEUE;
        return run(clusterId, kind, queueName, Map.of("paused", paused), dryRun, false, (client, broker) -> {
            String mbean = queueMbean(client, queue);
            if (ops.isPaused(client, mbean) == paused) {
                return NodeStatus.ALREADY;
            }
            if (paused) {
                ops.pause(client, mbean);
            } else {
                ops.resume(client, mbean);
            }
            return NodeStatus.APPLIED;
        });
    }

    @Transactional(noRollbackFor = {BulkCapExceededException.class, IllegalArgumentException.class})
    public Attempt<LifecycleOutcome> resetCounter(UUID clusterId, String queueName, boolean dryRun) {
        ResolvedQueue queue = resolveQueue(clusterId, queueName);
        return run(
                clusterId, LifecycleKind.RESET_QUEUE_COUNTER, queueName, Map.of(), dryRun, false, (client, broker) -> {
                    ops.resetMessageCounter(client, queueMbean(client, queue));
                    return NodeStatus.APPLIED;
                });
    }

    @Transactional(noRollbackFor = {BulkCapExceededException.class, IllegalArgumentException.class})
    public Attempt<LifecycleOutcome> createAddress(UUID clusterId, CreateAddressRequest req, boolean dryRun) {
        return run(
                clusterId,
                LifecycleKind.CREATE_ADDRESS,
                req.name(),
                Map.of("routingTypes", req.routingTypes()),
                dryRun,
                false,
                (client, broker) -> {
                    ops.createAddress(
                            client, broker, req.name(), req.routingTypes().toUpperCase());
                    return NodeStatus.APPLIED;
                });
    }

    /**
     * Delete an address, force-free (D8). The broker refuses while queues are bound;
     * the refusal is re-raised naming them, because the broker's own message does
     * not, and the operator deletes the queues explicitly. One click that destroys
     * an unbounded amount of data with no per-queue count in the confirmation is not
     * a safe default, and the safe path costs one extra step.
     */
    @Transactional(noRollbackFor = {BulkCapExceededException.class, IllegalArgumentException.class})
    public Attempt<LifecycleOutcome> deleteAddress(UUID clusterId, String address, boolean dryRun) {
        return run(clusterId, LifecycleKind.DELETE_ADDRESS, address, Map.of(), dryRun, false, (client, broker) -> {
            try {
                ops.deleteAddress(client, broker, address);
            } catch (ManagementRefusal e) {
                if (e.kind() == ManagementRefusal.Kind.BOUND_QUEUES) {
                    throw new ManagementRefusal(
                            ManagementRefusal.Kind.BOUND_QUEUES, boundQueuesMessage(client, broker, address));
                }
                throw e;
            }
            return NodeStatus.APPLIED;
        });
    }

    // ---- the fan-out -----------------------------------------------------

    /** What one node's attempt does; returns the status it reached. */
    @FunctionalInterface
    private interface NodeAction {
        NodeStatus apply(JolokiaBrokerClient client, String brokerMbean);
    }

    /** How many messages this command would destroy on a node, for the cap check (D6). */
    @FunctionalInterface
    private interface NodeEstimate {
        long apply(JolokiaBrokerClient client);
    }

    private Attempt<LifecycleOutcome> run(
            UUID clusterId,
            LifecycleKind kind,
            String targetName,
            Map<String, ?> params,
            boolean dryRun,
            boolean override,
            NodeAction action) {
        return run(clusterId, kind, targetName, params, dryRun, override, action, null);
    }

    private Attempt<LifecycleOutcome> run(
            UUID clusterId,
            LifecycleKind kind,
            String targetName,
            Map<String, ?> params,
            boolean dryRun,
            boolean override,
            NodeAction action,
            NodeEstimate estimate) {

        clusterAccess.requireCluster(clusterId, kind.permission());
        List<Target> targets = resolveTargets(clusterId);
        long cap = settings.bulkCap();

        AuditEventEntity event =
                audit.begin(actor(), kind.auditName(), kind.targetType(), targetName, clusterId, null, params, dryRun);

        List<NodeOutcome> outcomes = new ArrayList<>();
        long total = 0;

        // A destructive command is estimated across every live node first, so the cap
        // is checked against the whole blast radius rather than one node at a time
        // (D6) — and so a dry run reports a real number per node.
        Map<UUID, Long> estimates = new LinkedHashMap<>();
        if (estimate != null) {
            for (Target t : targets) {
                if (!t.live()) {
                    continue;
                }
                try {
                    estimates.put(t.node().getId(), estimate.apply(clientFor(clusterId, t.node())));
                } catch (ManagementRefusal e) {
                    // Nothing to destroy here — an absent queue counts as zero, and the
                    // action below reports the node as ALREADY.
                    estimates.put(t.node().getId(), 0L);
                } catch (BrokerConnectionException e) {
                    estimates.put(t.node().getId(), null);
                }
            }
            total = estimates.values().stream()
                    .filter(Objects::nonNull)
                    .mapToLong(Long::longValue)
                    .sum();
        }
        boolean overCap = estimate != null && total > cap;

        if (dryRun) {
            for (Target t : targets) {
                outcomes.add(
                        t.live()
                                ? new NodeOutcome(
                                        t.node().getId(),
                                        t.node().getName(),
                                        NodeStatus.WOULD_APPLY,
                                        estimates.get(t.node().getId()),
                                        null)
                                : NodeOutcome.skipped(t.node().getId(), t.node().getName()));
            }
            LifecycleOutcome outcome = new LifecycleOutcome(true, cap, overCap, outcomes);
            audit.finish(event, false, total, null, outcomes);
            return new Attempt.Ok<>(outcome);
        }

        if (overCap && !override) {
            String reason = "Over the safety cap (" + total + " > " + cap + ").";
            audit.finish(event, true, total, reason, outcomes);
            throw new BulkCapExceededException(total, cap);
        }

        for (Target t : targets) {
            if (!t.live()) {
                outcomes.add(NodeOutcome.skipped(t.node().getId(), t.node().getName()));
                continue;
            }
            outcomes.add(
                    applyTo(clusterId, t, kind, action, estimates.get(t.node().getId())));
        }

        LifecycleOutcome outcome = new LifecycleOutcome(false, cap, overCap, outcomes);
        long affected = outcome.totalAffected();
        audit.finish(event, outcome.anyFailed(), affected, failureSummary(outcomes), outcomes);
        publishQueuesAfterCommit(clusterId);
        return new Attempt.Ok<>(outcome);
    }

    /**
     * One node's attempt. Every failure mode is caught and turned into a node
     * outcome rather than aborting the fan-out: the nodes that succeeded are not
     * reverted, and the divergence is what gets reported (D3).
     */
    private NodeOutcome applyTo(UUID clusterId, Target t, LifecycleKind kind, NodeAction action, Long estimated) {
        UUID nodeId = t.node().getId();
        String nodeName = t.node().getName();
        try {
            JolokiaBrokerClient client = clientFor(clusterId, t.node());
            NodeStatus status = action.apply(client, client.resolveBrokerObjectName());
            capabilities.recordWriteSucceeded(clusterId);
            return new NodeOutcome(nodeId, nodeName, status, status == NodeStatus.APPLIED ? estimated : null, null);
        } catch (ManagementRefusal e) {
            if (e.kind() == ManagementRefusal.Kind.ALREADY) {
                // The node is already in the requested state. The broker answered, so
                // this is still evidence the connection can write.
                capabilities.recordWriteSucceeded(clusterId);
                return new NodeOutcome(nodeId, nodeName, NodeStatus.ALREADY, null, null);
            }
            // An argument refusal says the request is wrong, not that the connection
            // cannot write — it must never disable the capability (D5).
            return NodeOutcome.failed(nodeId, nodeName, e.getMessage());
        } catch (BrokerConnectionException e) {
            if (e.kind() == BrokerConnectionException.Kind.UNAUTHORIZED) {
                capabilities.recordWriteRefused(clusterId, e.getMessage());
            }
            return NodeOutcome.failed(nodeId, nodeName, e.getMessage());
        }
    }

    /**
     * The nodes a command targets, one per <em>logical</em> node.
     *
     * <p>A primary and its synced backup share an Artemis NodeID and are one logical
     * node; only one of them is live at a time, and the backup does not accept
     * management writes. Targeting the pair twice would report a healthy backup as a
     * skipped node on every command. Liveness comes from the polled {@code Active}
     * attribute, never from configuration (non-negotiable #4).
     */
    private List<Target> resolveTargets(UUID clusterId) {
        Map<String, List<BrokerNodeEntity>> logical = new LinkedHashMap<>();
        for (BrokerNodeEntity node : brokerNodes.findByClusterIdOrderByNameAsc(clusterId)) {
            if (node.getJolokiaUrl() == null) {
                continue;
            }
            logical.computeIfAbsent(logicalKey(node), k -> new ArrayList<>()).add(node);
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
                    "This cluster has no node with a management URL, so nothing can be applied to it.");
        }
        return targets;
    }

    private static String logicalKey(BrokerNodeEntity n) {
        return n.getArtemisNodeId() != null ? n.getArtemisNodeId() : "id:" + n.getId();
    }

    // ---- queue resolution ------------------------------------------------

    /** A queue's address and routing type, which the queue MBean object name needs. */
    record ResolvedQueue(String queueName, String address, String routingType) {}

    /**
     * The address and routing type for a queue name, from the cached snapshot —
     * never from the client, exactly as the message path does it. A queue Studio has
     * not scraped yet cannot be addressed, which is honest: it does not know where
     * the queue lives.
     */
    ResolvedQueue resolveQueue(UUID clusterId, String queueName) {
        return queueSnapshots.findByClusterId(clusterId).stream()
                .filter(s -> s.getQueueName().equals(queueName))
                .findFirst()
                .map(s -> new ResolvedQueue(queueName, s.getAddress(), s.getRoutingType()))
                .orElseThrow(() -> new NotFoundException("queue", queueName));
    }

    private static String queueMbean(JolokiaBrokerClient client, ResolvedQueue queue) {
        return BrokerMBeans.queue(
                client.resolveBrokerObjectName(), queue.address(), queue.queueName(), queue.routingType());
    }

    private String boundQueuesMessage(JolokiaBrokerClient client, String brokerMbean, String address) {
        List<String> queues = ops.boundQueues(client, BrokerMBeans.address(brokerMbean, address));
        if (queues.isEmpty()) {
            return "Address '" + address + "' still has queues bound to it.";
        }
        return "Address '" + address + "' still has " + queues.size() + " queue(s) bound to it: "
                + String.join(", ", queues) + ". Delete them first — this operation will not remove them for you.";
    }

    // ---- request -> broker configuration ---------------------------------

    private static Map<String, Object> queueConfig(CreateQueueRequest req) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", req.name());
        config.put("address", req.address());
        config.put("routing-type", req.routingType().toUpperCase());
        config.put("durable", req.durable());
        config.put("auto-create-address", req.autoCreateAddress());
        putIfPresent(config, "filter-string", blankToNull(req.filter()));
        putIfPresent(config, "max-consumers", req.maxConsumers());
        putIfPresent(config, "purge-on-no-consumers", req.purgeOnNoConsumers());
        putIfPresent(config, "exclusive", req.exclusive());
        putIfPresent(config, "non-destructive", req.nonDestructive());
        putIfPresent(config, "ring-size", req.ringSize());
        return config;
    }

    private static Map<String, Object> patch(UpdateQueueRequest req) {
        Map<String, Object> patch = new LinkedHashMap<>();
        // A filter is patchable to empty on purpose — clearing it is a real edit —
        // so it is included whenever the field was sent at all.
        putIfPresent(patch, "filter-string", req.filter());
        putIfPresent(patch, "max-consumers", req.maxConsumers());
        putIfPresent(patch, "purge-on-no-consumers", req.purgeOnNoConsumers());
        putIfPresent(patch, "exclusive", req.exclusive());
        putIfPresent(patch, "non-destructive", req.nonDestructive());
        putIfPresent(patch, "ring-size", req.ringSize());
        return patch;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Map<String, Object> params(CreateQueueRequest req) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("address", req.address());
        params.put("routingType", req.routingType());
        params.put("durable", req.durable());
        putIfPresent(params, "filter", blankToNull(req.filter()));
        return params;
    }

    /**
     * Whether a queue the broker reports already existing matches what was asked
     * for. {@code createQueue(config, ignoreIfExists = true)} answers 200 with the
     * <em>existing</em> configuration, so this is what separates "already in the
     * requested state" from "exists, but differs" — and a mismatch must be reported
     * rather than passed off as success (the {@code ALREADY} risk in the design).
     */
    private static boolean matchesRequest(JsonNode existing, CreateQueueRequest req) {
        if (existing == null || !existing.isObject()) {
            return false;
        }
        return sameText(existing, "address", req.address())
                && sameText(existing, "routing-type", req.routingType().toUpperCase())
                && sameText(existing, "filter-string", blankToNull(req.filter()))
                && sameBoolean(existing, "durable", req.durable())
                && sameNumber(existing, "max-consumers", req.maxConsumers())
                && sameBoolean(existing, "purge-on-no-consumers", req.purgeOnNoConsumers())
                && sameBoolean(existing, "exclusive", req.exclusive())
                && sameBoolean(existing, "non-destructive", req.nonDestructive())
                && sameNumber(existing, "ring-size", req.ringSize());
    }

    /**
     * The queue configuration read back from a node's MBean, as a JSON object, so
     * the same comparison works against it and against a broker-returned document.
     */
    private JsonNode asConfigNode(Map<String, Object> config) {
        return mapper.valueToTree(config);
    }

    /** A field the request did not specify is not a difference — the broker's default stands. */
    private static boolean sameText(JsonNode node, String key, String expected) {
        if (expected == null) {
            return true;
        }
        JsonNode v = node.get(key);
        return v != null && !v.isNull() && expected.equals(v.asString());
    }

    private static boolean sameBoolean(JsonNode node, String key, Boolean expected) {
        if (expected == null) {
            return true;
        }
        JsonNode v = node.get(key);
        return v != null && !v.isNull() && v.asBoolean() == expected;
    }

    private static boolean sameNumber(JsonNode node, String key, Number expected) {
        if (expected == null) {
            return true;
        }
        JsonNode v = node.get(key);
        return v != null && !v.isNull() && v.asLong() == expected.longValue();
    }

    /** Describe a differing existing queue well enough that the operator can act on it. */
    static String describeDifference(JsonNode existing, CreateQueueRequest req) {
        List<String> differences = new ArrayList<>();
        if (!sameText(existing, "address", req.address())) {
            differences.add("address is " + text(existing, "address") + ", requested " + req.address());
        }
        if (!sameText(existing, "routing-type", req.routingType().toUpperCase())) {
            differences.add("routing type is " + text(existing, "routing-type") + ", requested " + req.routingType());
        }
        if (!sameText(existing, "filter-string", blankToNull(req.filter()))) {
            differences.add("filter is " + text(existing, "filter-string") + ", requested " + req.filter());
        }
        return differences.isEmpty() ? "the existing queue differs from the request" : String.join("; ", differences);
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v == null || v.isNull() ? "unset" : v.asString();
    }

    // ---- plumbing --------------------------------------------------------

    private static String failureSummary(List<NodeOutcome> outcomes) {
        List<String> failed = outcomes.stream()
                .filter(n -> n.status() == NodeStatus.FAILED)
                .map(n -> n.nodeName() + ": " + n.error())
                .sorted(Comparator.naturalOrder())
                .toList();
        return failed.isEmpty() ? null : String.join(" | ", failed);
    }

    private JolokiaBrokerClient clientFor(UUID clusterId, BrokerNodeEntity node) {
        acquire(node.getId());
        return connections.forCluster(clusterId, node.getJolokiaUrl());
    }

    private void acquire(UUID nodeId) {
        try {
            limiter.acquire(nodeId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE, "Timed out waiting for a per-node call permit.");
        }
    }

    private Actor actor() {
        return actorResolver.resolve();
    }

    private void publishQueuesAfterCommit(UUID clusterId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sseHub.publish(clusterId, "queues");
                }
            });
        } else {
            sseHub.publish(clusterId, "queues");
        }
    }
}
