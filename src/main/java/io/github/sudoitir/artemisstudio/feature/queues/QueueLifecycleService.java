package io.github.sudoitir.artemisstudio.feature.queues;

import io.github.sudoitir.artemisstudio.feature.queues.LifecycleRequests.CreateAddressRequest;
import io.github.sudoitir.artemisstudio.feature.queues.LifecycleRequests.CreateDivertRequest;
import io.github.sudoitir.artemisstudio.feature.queues.LifecycleRequests.CreateQueueRequest;
import io.github.sudoitir.artemisstudio.feature.queues.LifecycleRequests.UpdateQueueRequest;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.stream.SseHub;
import io.github.sudoitir.artemisstudio.platform.broker.Attempt;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerMBeans;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.ManagementRefusal;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCommands;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCommands.Check;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCommands.Command;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCommands.Estimate;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCommands.NodeAction;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueLocator;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshotUpsert;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Queue, address and divert lifecycle across a cluster (ADR-0049).
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

    private final QueueLocator queueLocator;
    private final QueueSnapshotUpsert snapshotWriter;
    private final Optional<DeclaredDiverts> declaredDiverts;
    private final Optional<CaptureTaps> captureTaps;
    private final QueueLifecycleOperations ops;
    private final DivertOperations divertOps;
    private final SseHub sseHub;
    private final BrokerCommands commands;
    private final ObjectMapper mapper;
    private final jakarta.validation.Validator validator;

    // ---- entry points ----------------------------------------------------

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

    /**
     * Delete a queue on every live node (ADR-0084). Each node is checked first, in the
     * preview and again for real: a queue with consumers is refused unless the operator asked
     * to disconnect them, and the diverts that forward only into this queue are named. For
     * real, those diverts go first and the queue after, so a divert that cannot be removed
     * leaves its queue in place rather than a divert forwarding into nothing.
     */
    public Attempt<LifecycleOutcome> deleteQueue(
            UUID clusterId, String queueName, boolean dryRun, boolean override, boolean disconnectConsumers) {
        ResolvedQueue queue = resolveQueue(clusterId, queueName);
        Set<String> declared = declaredDiverts.map(d -> d.names(clusterId)).orElse(Set.of());
        LifecycleKind kind = LifecycleKind.DELETE_QUEUE;
        return new Attempt.Ok<>(commands.run(Command.builder()
                .clusterId(clusterId)
                .permission(kind.permission())
                .auditAction(kind.auditName())
                .targetType(kind.targetType())
                .targetName(queueName)
                .params(Map.of("disconnectConsumers", disconnectConsumers))
                .dryRun(dryRun)
                .override(override)
                .preflight((client, broker) -> deletePreflight(
                        deletePlan(clusterId, client, broker, queue), queue, disconnectConsumers, declared))
                .action((client, broker) -> deleteWithDiverts(
                        client, broker, deletePlan(clusterId, client, broker, queue), disconnectConsumers))
                .estimate(new Estimate(
                        "message count", false, client -> ops.messageCount(client, queueMbean(client, queue))))
                .signal(() -> {
                    // The queue is gone from the brokers; the snapshot is a cache of
                    // what they run. Left alone it keeps the row until the next sweep,
                    // so the delete reads as though it did nothing and the row that is
                    // left fails every action against a destroyed MBean.
                    snapshotWriter.forget(clusterId, queueName);
                    sseHub.publish(clusterId, "queues");
                })
                .build()));
    }

    /**
     * One node's view of a queue delete.
     *
     * @param dependents diverts that forward into the queue's address when the delete leaves that
     *     address with no binding — removed with it (D1)
     * @param keptIncoming diverts into the address that stay, because a divert from the address
     *     keeps it bound and routing (ADR-0085)
     * @param kept diverts whose source is the queue's address — they still route (D4)
     * @param tapsGone capture taps their subscription removes once the queue is gone (D5)
     * @param tapsKept capture taps their subscription keeps for another queue on the address (D5)
     */
    private record DeletePlan(
            ResolvedQueue queue,
            QueueLifecycleOperations.DeleteState state,
            List<DivertRow> dependents,
            List<DivertRow> keptIncoming,
            List<DivertRow> kept,
            List<DivertRow> tapsGone,
            List<DivertRow> tapsKept) {}

    private DeletePlan deletePlan(UUID clusterId, JolokiaBrokerClient client, String broker, ResolvedQueue queue) {
        var state = ops.deleteState(client, broker, queue.address(), queue.queueName(), queue.routingType());
        if (!state.present()) {
            return new DeletePlan(queue, state, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        String address = queue.address();
        List<DivertRow> incoming = new ArrayList<>();
        List<DivertRow> kept = new ArrayList<>();
        List<DivertRow> tapsGone = new ArrayList<>();
        List<DivertRow> tapsKept = new ArrayList<>();
        for (DivertRow d : divertOps.listDiverts(client, null, null)) {
            boolean capture = d.uniqueName() != null && d.uniqueName().startsWith(DivertOperations.CAPTURE_PREFIX);
            if (capture) {
                if (address.equals(d.address())) {
                    boolean stays = captureTaps
                            .map(t -> t.coversWithout(clusterId, d.uniqueName(), address, queue.queueName()))
                            .orElse(false);
                    (stays ? tapsKept : tapsGone).add(d);
                }
            } else if (address.equals(d.forwardingAddress())) {
                incoming.add(d);
            } else if (address.equals(d.address())) {
                kept.add(d);
            }
        }
        // Only an address left with no binding at all breaks or re-creates through an incoming
        // divert. A divert from it keeps it bound and routing, so the incoming one still feeds it.
        boolean lastQueue = state.addressQueues().equals(List.of(queue.queueName()));
        boolean orphaned = lastQueue && kept.isEmpty();
        return new DeletePlan(
                queue,
                state,
                orphaned ? incoming : List.of(),
                lastQueue && !orphaned ? incoming : List.of(),
                kept,
                tapsGone,
                tapsKept);
    }

    private static Check deletePreflight(
            DeletePlan plan, ResolvedQueue queue, boolean disconnectConsumers, Set<String> declared) {
        if (!plan.state().present()) {
            // Nothing here to delete; the node reports it as already gone.
            return Check.OK;
        }
        long consumers = plan.state().consumerCount();
        String counted = consumers + (consumers == 1 ? " consumer" : " consumers");
        if (consumers > 0 && !disconnectConsumers) {
            return Check.refuse("Queue '" + queue.queueName() + "' has " + counted + " attached on this node, and the"
                    + " broker will not delete it while they are. Stop the consumers, or tick \"Disconnect this"
                    + " queue's consumers\" (disconnectConsumers=true over the API or MCP) to close them.");
        }
        List<String> notes = new ArrayList<>();
        if (consumers > 0) {
            notes.add(counted + " will be disconnected. A client that reconnects can create the queue again if"
                    + " auto-create is on for '" + queue.address() + "'.");
        }
        if (!plan.dependents().isEmpty()) {
            notes.add("Removed with the queue, because they forward into '" + queue.address()
                    + "' and the delete leaves it with nothing bound: " + describe(plan.dependents())
                    + ". If one is in this node's"
                    + " broker.xml, the broker creates it again at its next restart, and the queue can come back"
                    + " with it.");
            List<String> redeclared = plan.dependents().stream()
                    .map(DivertRow::uniqueName)
                    .filter(declared::contains)
                    .toList();
            if (!redeclared.isEmpty()) {
                notes.add(quoted(redeclared) + " in this cluster's declared configuration: the next apply, or the"
                        + " next broker.xml rendered from it, brings it back. Remove it from the declaration.");
            }
        }
        if (!plan.keptIncoming().isEmpty()) {
            notes.add("Diverts into '" + queue.address() + "' are kept, although this is its last queue, because"
                    + " the diverts from it keep it bound and still route what they forward: "
                    + quoted(plan.keptIncoming().stream()
                            .map(DivertRow::uniqueName)
                            .toList()) + ".");
        }
        if (!plan.kept().isEmpty()) {
            notes.add("Diverts from '" + queue.address() + "' are kept, because they still route what producers"
                    + " send to it: "
                    + quoted(plan.kept().stream().map(DivertRow::uniqueName).toList()) + ".");
        }
        if (!plan.tapsGone().isEmpty()) {
            notes.add("Capture taps on '" + queue.address() + "', each removed by its capture subscription once"
                    + " the scrape no longer shows the queue: "
                    + quoted(plan.tapsGone().stream().map(DivertRow::uniqueName).toList()) + ".");
        }
        if (!plan.tapsKept().isEmpty()) {
            notes.add("Capture taps on '" + queue.address() + "' are kept, because their subscription still"
                    + " captures another queue there: "
                    + quoted(plan.tapsKept().stream().map(DivertRow::uniqueName).toList()) + ".");
        }
        return notes.isEmpty() ? Check.OK : Check.warn(String.join(" ", notes));
    }

    /** The dependent diverts first, then the queue (D3). Nothing is rolled back. */
    private NodeStatus deleteWithDiverts(
            JolokiaBrokerClient client, String broker, DeletePlan plan, boolean disconnectConsumers) {
        List<DivertRow> removed = new ArrayList<>();
        for (DivertRow d : plan.dependents()) {
            try {
                divertOps.destroyDivert(client, broker, d.uniqueName());
            } catch (ManagementRefusal e) {
                if (e.kind() != ManagementRefusal.Kind.ALREADY) {
                    throw new ManagementRefusal(e.kind(), divertFailed(d, e, removed));
                }
            } catch (BrokerConnectionException e) {
                throw new BrokerConnectionException(e.kind(), divertFailed(d, e, removed));
            }
            removed.add(d);
        }
        String gone = removed.isEmpty() ? "" : " Diverts already removed from this node: " + describe(removed) + ".";
        try {
            ops.destroyQueue(client, broker, plan.queue().queueName(), disconnectConsumers);
        } catch (ManagementRefusal e) {
            throw e.kind() == ManagementRefusal.Kind.ALREADY
                    ? e
                    : new ManagementRefusal(e.kind(), e.getMessage() + gone);
        } catch (BrokerConnectionException e) {
            throw new BrokerConnectionException(e.kind(), e.getMessage() + gone);
        }
        return NodeStatus.APPLIED;
    }

    private static String divertFailed(DivertRow d, RuntimeException cause, List<DivertRow> removed) {
        return "Divert '" + d.uniqueName() + "' could not be removed (" + cause.getMessage()
                + "), so the queue was not deleted on this node."
                + (removed.isEmpty() ? "" : " Diverts already removed from this node: " + describe(removed) + ".");
    }

    /** Everything needed to recreate each divert exactly, as the audit row keeps it (D3). */
    private static String describe(List<DivertRow> diverts) {
        return diverts.stream()
                .map(d -> "'" + d.uniqueName() + "' (" + d.address() + " → " + d.forwardingAddress()
                        + ", routing name " + d.routingName()
                        + ", routing type " + d.routingType()
                        + (d.exclusive() ? ", exclusive" : "")
                        + (d.filter() == null || d.filter().isBlank() ? "" : ", filter " + d.filter())
                        + (d.transformerClassName() == null
                                        || d.transformerClassName().isBlank()
                                ? ""
                                : ", transformer " + d.transformerClassName())
                        + ")")
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static String quoted(List<String> names) {
        return names.stream().map(n -> "'" + n + "'").collect(java.util.stream.Collectors.joining(", "));
    }

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

    public Attempt<LifecycleOutcome> resetCounter(UUID clusterId, String queueName, boolean dryRun) {
        ResolvedQueue queue = resolveQueue(clusterId, queueName);
        return run(
                clusterId, LifecycleKind.RESET_QUEUE_COUNTER, queueName, Map.of(), dryRun, false, (client, broker) -> {
                    ops.resetMessageCounter(client, queueMbean(client, queue));
                    return NodeStatus.APPLIED;
                });
    }

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

    // ---- diverts ---------------------------------------------------------

    /**
     * Create a divert on every live node. A divert exists per node, so this is the
     * same fan-out every other topology mutation uses, with the same per-node
     * outcomes and the same preview.
     *
     * <p>Each node is checked first, in the preview and again for real: a forwarding
     * address that is not there would make producers to the source fail, and a cycle
     * would copy a message around it indefinitely. The result is what the broker
     * deployed, read back — an identical divert already there is {@code ALREADY}, a
     * different one under the same name fails naming the difference.
     */
    public Attempt<LifecycleOutcome> createDivert(UUID clusterId, CreateDivertRequest req, boolean dryRun) {
        requireValid(req);
        refuseCaptureName(req.name());
        Map<String, Object> config = DivertOperations.divertConfig(
                req.name(),
                req.routingName(),
                req.address(),
                req.forwardingAddress(),
                Boolean.TRUE.equals(req.exclusive()),
                req.filter(),
                req.routingType());
        Map<String, Object> audited = new LinkedHashMap<>(config);
        audited.put("acknowledgeCaptureShadowing", req.acknowledgeCaptureShadowing());
        return new Attempt.Ok<>(commands.run(Command.builder()
                .clusterId(clusterId)
                .permission(LifecycleKind.CREATE_DIVERT.permission())
                .auditAction(LifecycleKind.CREATE_DIVERT.auditName())
                .targetType(LifecycleKind.CREATE_DIVERT.targetType())
                .targetName(req.name())
                .params(audited)
                .dryRun(dryRun)
                .preflight((client, broker) -> divertPreflight(client, broker, req))
                .action((client, broker) -> divertOps.createVerified(client, broker, config))
                .signal(() -> sseHub.publish(clusterId, "queues"))
                .build()));
    }

    private Check divertPreflight(JolokiaBrokerClient client, String broker, CreateDivertRequest req) {
        List<DivertRow> existing = divertOps.listDiverts(client, null, null);
        String cycle = DivertOperations.cycle(existing, req.name(), req.address(), req.forwardingAddress());
        if (cycle != null) {
            return Check.refuse("This divert would complete a cycle of diverts: " + cycle
                    + ". A message would be copied around it without end.");
        }
        if (!divertOps.addressAvailable(client, broker, req.forwardingAddress())) {
            return Check.refuse("The forwarding address '" + req.forwardingAddress() + "' does not exist on this node"
                    + " and is not created automatically, so producers to '" + req.address() + "' would fail."
                    + " Create the address first, or declare it in broker.xml: <addresses><address name=\""
                    + req.forwardingAddress() + "\"><anycast/></address></addresses>");
        }
        boolean captured = existing.stream()
                .anyMatch(d -> d.uniqueName() != null
                        && d.uniqueName().startsWith(DivertOperations.CAPTURE_PREFIX)
                        && req.address().equals(d.address()));
        if (Boolean.TRUE.equals(req.exclusive()) && captured) {
            String effect = "'" + req.address() + "' is being captured, and Artemis applies exclusive diverts before"
                    + " every other one: capture of it would observe nothing while this divert exists.";
            return Boolean.TRUE.equals(req.acknowledgeCaptureShadowing())
                    ? Check.warn(effect)
                    : Check.refuse(effect + " Set acknowledgeCaptureShadowing to create it anyway.");
        }
        return Check.OK;
    }

    /** Bean validation for callers that do not pass through a validated controller argument (MCP). */
    private void requireValid(CreateDivertRequest req) {
        var violations = validator.validate(req);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .collect(java.util.stream.Collectors.joining("; ")));
        }
    }

    /** Capture's diverts are managed by their subscription (ADR-0079), whichever API is asked. */
    private static void refuseCaptureName(String name) {
        if (name != null && name.startsWith(DivertOperations.CAPTURE_PREFIX)) {
            throw new IllegalArgumentException("'" + name + "' is in the namespace reserved for message capture ("
                    + DivertOperations.CAPTURE_PREFIX + "). Manage it from its capture subscription instead.");
        }
    }

    /**
     * Delete a divert from every live node. Nothing else removes one — a divert
     * created over management outlives the broker process (ADR-0065) — so this is
     * the only path, and the confirmation says so rather than implying a restart
     * would do it.
     */
    public Attempt<LifecycleOutcome> deleteDivert(UUID clusterId, String name, boolean dryRun) {
        refuseCaptureName(name);
        return run(clusterId, LifecycleKind.DELETE_DIVERT, name, Map.of(), dryRun, false, (client, broker) -> {
            divertOps.destroyDivert(client, broker, name);
            return NodeStatus.APPLIED;
        });
    }

    // ---- the fan-out -----------------------------------------------------

    private Attempt<LifecycleOutcome> run(
            UUID clusterId,
            LifecycleKind kind,
            String targetName,
            Map<String, ?> params,
            boolean dryRun,
            boolean override,
            NodeAction action) {
        return new Attempt.Ok<>(commands.run(Command.builder()
                .clusterId(clusterId)
                .permission(kind.permission())
                .auditAction(kind.auditName())
                .targetType(kind.targetType())
                .targetName(targetName)
                .params(params)
                .dryRun(dryRun)
                .override(override)
                .action(action)
                .signal(() -> sseHub.publish(clusterId, "queues"))
                .build()));
    }

    // ---- queue resolution ------------------------------------------------

    /** A queue's address and routing type, which the queue MBean object name needs. */
    record ResolvedQueue(String queueName, String address, String routingType) {}

    /**
     * The address and routing type for a queue name — never from the client, exactly as the
     * message path does it. The scraped snapshot answers first, and a queue the scrape has not
     * reached yet is looked up on the live nodes. One no node has is not found.
     */
    ResolvedQueue resolveQueue(UUID clusterId, String queueName) {
        return queueLocator.locate(clusterId, queueName).stream()
                .findFirst()
                .map(l -> new ResolvedQueue(queueName, l.address(), l.routingType()))
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
}
