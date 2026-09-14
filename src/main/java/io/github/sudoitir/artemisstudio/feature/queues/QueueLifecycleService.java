package io.github.sudoitir.artemisstudio.feature.queues;

import io.github.sudoitir.artemisstudio.feature.queues.LifecycleRequests.CreateAddressRequest;
import io.github.sudoitir.artemisstudio.feature.queues.LifecycleRequests.CreateDivertRequest;
import io.github.sudoitir.artemisstudio.feature.queues.LifecycleRequests.CreateQueueRequest;
import io.github.sudoitir.artemisstudio.feature.queues.LifecycleRequests.UpdateQueueRequest;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.stream.SseHub;
import io.github.sudoitir.artemisstudio.platform.broker.Attempt;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerMBeans;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.ManagementRefusal;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCommands;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCommands.Check;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCommands.Command;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCommands.Estimate;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCommands.NodeAction;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCommands.NodeEstimate;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshots;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final QueueSnapshots queueSnapshots;
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
                .estimate(estimate == null ? null : new Estimate("message count", false, estimate))
                .signal(() -> sseHub.publish(clusterId, "queues"))
                .build()));
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
        return queueSnapshots.forCluster(clusterId).stream()
                .filter(s -> s.queueName().equals(queueName))
                .findFirst()
                .map(s -> new ResolvedQueue(queueName, s.address(), s.routingType()))
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
