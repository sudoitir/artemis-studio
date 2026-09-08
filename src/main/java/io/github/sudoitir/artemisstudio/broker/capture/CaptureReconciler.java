package io.github.sudoitir.artemisstudio.broker.capture;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.CaptureMode;
import io.github.sudoitir.artemisstudio.persist.CaptureState;
import io.github.sudoitir.artemisstudio.persist.ClusterLock;
import io.github.sudoitir.artemisstudio.persist.MessageCaptureNodeEntity;
import io.github.sudoitir.artemisstudio.persist.MessageCaptureNodeRepository;
import io.github.sudoitir.artemisstudio.persist.MessageIndexSubscriptionEntity;
import io.github.sudoitir.artemisstudio.persist.MessageIndexSubscriptionRepository;
import io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.security.Actor;
import io.github.sudoitir.artemisstudio.service.ServingNodes;
import io.github.sudoitir.artemisstudio.sql.QueryPlan;
import io.github.sudoitir.artemisstudio.sql.QueryPlanner;
import io.github.sudoitir.artemisstudio.sql.SqlQueryParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Converges the capture taps that should exist onto the ones that do (ADR-0062 D4).
 *
 * <p>This is the only lifecycle mechanism capture has. There is no separate install
 * flow to keep in step with it: creating a capture subscription writes a row, and the
 * next pass makes it true. That one property makes this simultaneously the
 * crash-recovery path, the failover path, the broker-restart path and the new-queue
 * path — a promoted backup carries no capture divert, and the next pass installs one.
 *
 * <p>Desired state is the {@code CAPTURE} subscriptions in Postgres, expanded through
 * the same planner an operator's own query uses, so "which queues does this pattern
 * mean" has exactly one answer in the product. Actual state is each node's own divert
 * names, filtered to this instance's prefix.
 *
 * <p>Two ownership rules, and they are not the same rule. A divert belonging to
 * another Studio is never touched — its name says so, and nothing else could tell us
 * it exists. A second instance of <em>this</em> Studio shares the name and the
 * database, so the whole pass is taken under a per-cluster advisory lock; not holding
 * it means the other instance is doing this work, which is a reason to stop rather
 * than a failure to report.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CaptureReconciler {

    private final MessageIndexSubscriptionRepository subscriptions;
    private final MessageCaptureNodeRepository captureNodes;
    private final BrokerNodeRepository nodes;
    private final BrokerConnections connections;
    private final NodeCallLimiter limiter;
    private final ClusterLock clusterLock;
    private final CaptureTap tap;
    private final CaptureConsumer consumers;
    private final CaptureBus bus;
    private final StudioInstance instance;
    private final SqlQueryParser parser;
    private final QueryPlanner planner;
    private final AuditService audit;
    private final CaptureLoss loss;

    /**
     * Clusters this instance has installed a tap on. In memory, because it exists only
     * to keep sweeping a cluster after its subscriptions are gone; a restart with no
     * subscription left means the taps were already removed, and a restart with one
     * left repopulates this on the next install.
     */
    private final Set<UUID> installedOn = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * One sweep of every cluster Studio knows about, once, at startup.
     *
     * <p>A tap outlives the process that created it (ADR-0065), so a Studio that was
     * killed between a subscription's deletion and the sweep that should have followed
     * comes back with an orphan nothing else would ever visit — and an orphaned divert
     * pointing at a deleted address makes the <em>source</em> address unusable, which
     * is precisely the kind of harm Studio must never do to a broker. One bounded pass
     * at startup closes that, and costs one divert listing per node, once.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        for (BrokerNodeEntity node : nodes.findAll()) {
            installedOn.add(node.getClusterId());
        }
        reconcile();
    }

    /** Registered with {@code DynamicSchedules}. One pass per cluster that captures anything. */
    public void reconcile() {
        for (UUID clusterId : capturingClusters()) {
            clusterLock.runIfHeld(clusterId, () -> {
                try {
                    reconcileCluster(clusterId);
                } catch (RuntimeException e) {
                    log.warn("Capture reconcile for cluster {} did not complete: {}", clusterId, e.getMessage());
                }
            });
        }
    }

    /**
     * The clusters a pass visits: those with a capture subscription, plus any this
     * instance has installed a tap on since it started.
     *
     * <p>The second half is what makes removal work. Deleting the last capture
     * subscription for a cluster deletes its per-node state with it, so a pass driven
     * only by subscriptions would stop visiting exactly the cluster that still has an
     * orphan on it. Clusters Studio has never touched are never contacted, so this
     * costs nothing where there is nothing to do.
     */
    private Set<UUID> capturingClusters() {
        Set<UUID> ids = new LinkedHashSet<>(installedOn);
        for (MessageIndexSubscriptionEntity subscription : subscriptions.findByEnabledTrue()) {
            if (subscription.getMode() == CaptureMode.CAPTURE) {
                ids.add(subscription.getClusterId());
            }
        }
        return ids;
    }

    // ---- one cluster -----------------------------------------------------

    /**
     * Deliberately not transactional. A pass makes management calls to every live
     * node, and a database transaction held open across broker HTTP would tie up a
     * connection for as long as the slowest broker takes to answer. Each write here
     * is its own short transaction, and the loop is idempotent, so a pass that dies
     * half-way is simply repeated.
     */
    public void reconcileCluster(UUID clusterId) {
        List<BrokerNodeEntity> serving = servingNodes(clusterId);
        List<MessageIndexSubscriptionEntity> capturing = subscriptions.findByEnabledTrue().stream()
                .filter(s -> clusterId.equals(s.getClusterId()))
                .filter(s -> s.getMode() == CaptureMode.CAPTURE)
                .toList();

        Map<UUID, List<Desired>> desiredByNode = desired(clusterId, capturing, serving);

        for (BrokerNodeEntity node : serving) {
            List<Desired> wanted = desiredByNode.getOrDefault(node.getId(), List.of());
            log.debug("Capture pass on {}: {} tap(s) wanted", node.getName(), wanted.size());
            try {
                reconcileNode(clusterId, node, wanted);
            } catch (RuntimeException e) {
                // Unreachable is transient and is retried, so it is not FAILED. It is
                // also not nothing: a node Studio cannot reach is a node it is not
                // capturing, and leaving the last known state standing would report
                // coverage that is not happening (D4).
                log.warn("Capture reconcile could not finish on {}: {}", node.getName(), e.toString());
                for (Desired desired : wanted) {
                    state(
                            desired,
                            node,
                            CaptureState.PENDING,
                            "This node did not answer, so capture could not be asserted on it: " + e.getMessage(),
                            false);
                }
            }
        }
        loss.measure(clusterId);
    }

    /** One tap that should exist: a subscription's source address on a node. */
    private record Desired(MessageIndexSubscriptionEntity subscription, String address, String name) {}

    /**
     * The taps that should exist: every address a subscription resolves to, on every
     * serving node.
     *
     * <p>The address set is resolved cluster-wide and then asserted per node, not
     * resolved per node. That distinction is what makes failover work. A promoted
     * backup has no {@code queue_snapshot} rows until the scrape reaches it, which can
     * be minutes; resolving per node would leave the newly live node untapped for
     * exactly that window — the window an operator is most likely to be watching. A
     * divert on an address a node has not seen traffic for yet costs nothing and is
     * ready when it does.
     *
     * <p>A pattern that resolves to nothing yields nothing, and whatever it matched
     * before becomes an orphan that the same pass removes.
     */
    private Map<UUID, List<Desired>> desired(
            UUID clusterId, List<MessageIndexSubscriptionEntity> capturing, List<BrokerNodeEntity> serving) {
        Map<UUID, List<Desired>> byNode = new LinkedHashMap<>();
        for (MessageIndexSubscriptionEntity subscription : capturing) {
            for (String address : addressesFor(clusterId, subscription)) {
                for (BrokerNodeEntity node : serving) {
                    byNode.computeIfAbsent(node.getId(), k -> new ArrayList<>())
                            .add(new Desired(
                                    subscription,
                                    address,
                                    CaptureNames.of(instance.id(), address, subscription.getId())));
                }
            }
        }
        return byNode;
    }

    /**
     * The addresses one subscription covers, through the same planner an operator's own
     * query uses — so "which queues does this pattern mean" has exactly one answer in
     * the product. One capture queue per address however many queues are bound to it: a
     * divert copies at address routing (D3).
     */
    private Set<String> addressesFor(UUID clusterId, MessageIndexSubscriptionEntity subscription) {
        QueryPlan plan;
        try {
            plan = planner.plan(
                    clusterId, parser.parse("SELECT * FROM broker.\"" + subscription.getQueuePattern() + '"'));
        } catch (RuntimeException e) {
            log.debug("Capture pattern '{}' could not be resolved: {}", subscription.getQueuePattern(), e.getMessage());
            return Set.of();
        }
        Set<String> addresses = new LinkedHashSet<>();
        for (QueryPlan.Target target : plan.targets()) {
            addresses.add(target.address() == null ? target.queueName() : target.address());
        }
        return addresses;
    }

    // ---- one node --------------------------------------------------------

    private void reconcileNode(UUID clusterId, BrokerNodeEntity node, List<Desired> wanted) {
        JolokiaBrokerClient client = client(clusterId, node);
        Set<String> actual = new LinkedHashSet<>(tap.installedNames(client, instance.id()));
        log.debug("Capture pass on {}: {} tap(s) already installed", node.getName(), actual.size());
        Set<String> wantedNames = new LinkedHashSet<>();
        wanted.forEach(d -> wantedNames.add(d.name()));

        // Drift only. A pass that re-asserts what is already there would be one
        // management write per tap per interval, forever, for no change (D4).
        for (Desired d : wanted) {
            // Draining is the whole-tap health check. A divert alone is not enough: a
            // promoted backup inherits the divert through the bindings journal but not
            // the non-durable capture queue, so "the divert is there" would skip
            // exactly the node that needs the queue rebuilt.
            if (consumers.isDraining(node.getId(), d.name())) {
                continue;
            }
            install(clusterId, node, d);
        }

        for (String orphan : actual) {
            if (!wantedNames.contains(orphan)) {
                removeOrphan(clusterId, node, client, orphan);
            }
        }
        if (wanted.isEmpty() && actual.isEmpty()) {
            // Nothing wanted and nothing left: stop visiting this cluster until
            // something asks for a tap again.
            installedOn.remove(clusterId);
        }

        // A drain for a tap this node no longer has is stopped whatever the reason it
        // went away — including a broker that was rebuilt underneath us.
        for (String draining : consumers.drainingOn(node.getId())) {
            if (!wantedNames.contains(draining) && !actual.contains(draining)) {
                consumers.stop(node.getId(), draining);
            }
        }
    }

    private void install(UUID clusterId, BrokerNodeEntity node, Desired desired) {
        AuditEventEntity event = audit.begin(
                Actor.system(),
                "INSTALL_CAPTURE",
                "CAPTURE",
                desired.name(),
                clusterId,
                node.getId(),
                Map.of(
                        "address",
                        desired.address(),
                        "subscription",
                        desired.subscription().getId()),
                false);
        try {
            // Idempotent in both directions, so it is safe to run whenever this node
            // is not already draining — which is the only time it runs.
            tap.install(
                    client(clusterId, node),
                    instance.id(),
                    new CaptureTap.Spec(
                            desired.subscription().getId(),
                            desired.address(),
                            desired.subscription().getRingSize(),
                            desired.subscription().getFilterString()));
            startDraining(clusterId, node, desired);
            installedOn.add(clusterId);
            state(desired, node, CaptureState.ACTIVE, null, true);
            audit.succeed(event, 1);
        } catch (CaptureRefusedException e) {
            // A statement about the broker's configuration, not a transient failure:
            // the next pass will get the same answer, so it is recorded as the reason
            // this node is not captured rather than retried silently.
            state(desired, node, CaptureState.FAILED, e.getMessage(), false);
            audit.fail(event, e.getMessage());
        } catch (RuntimeException | jakarta.jms.JMSException e) {
            state(desired, node, CaptureState.PENDING, reason(e), false);
            audit.fail(event, reason(e));
        }
    }

    private void startDraining(UUID clusterId, BrokerNodeEntity node, Desired desired) throws jakarta.jms.JMSException {
        if (node.getCoreUrl() == null) {
            throw new CaptureRefusedException(
                    "This node has no Core URL registered, and capture is drained over the Core protocol. "
                            + "Add one to the cluster's connection and capture starts on the next pass.",
                    null);
        }
        consumers.start(new CaptureConsumer.Spec(
                clusterId,
                node.getId(),
                node.getName(),
                node.getCoreUrl(),
                desired.subscription().getId(),
                desired.name(),
                CaptureNames.queueOf(desired.name()),
                desired.address(),
                desired.subscription().getBodyCapBytes(),
                desired.subscription().getMaxRate()));
    }

    private void removeOrphan(UUID clusterId, BrokerNodeEntity node, JolokiaBrokerClient client, String name) {
        AuditEventEntity event = audit.begin(
                Actor.system(), "REMOVE_CAPTURE", "CAPTURE", name, clusterId, node.getId(), Map.of(), false);
        try {
            consumers.stop(node.getId(), name);
            tap.remove(client, instance.id(), name);
            UUID subscriptionId = CaptureNames.subscriptionOf(name);
            if (subscriptionId != null) {
                captureNodes.deleteBySubscriptionIdAndNodeId(subscriptionId, node.getId());
                if (subscriptions.findById(subscriptionId).isEmpty()) {
                    bus.forget(subscriptionId);
                }
            }
            audit.succeed(event, 1);
        } catch (RuntimeException e) {
            audit.fail(event, reason(e));
        }
    }

    // ---- per-node state --------------------------------------------------

    private void state(Desired desired, BrokerNodeEntity node, CaptureState newState, String detail, boolean covered) {
        MessageCaptureNodeEntity row = captureNodes
                .findBySubscriptionIdAndNodeId(desired.subscription().getId(), node.getId())
                .orElseGet(() -> {
                    MessageCaptureNodeEntity fresh = new MessageCaptureNodeEntity();
                    fresh.setSubscriptionId(desired.subscription().getId());
                    fresh.setNodeId(node.getId());
                    return fresh;
                });
        // capturedFrom is set when coverage starts and cleared when it stops, never
        // carried across a gap: a re-install after a failover does not cover the
        // interval the node was live and untapped (D4). The gap itself is stated, so
        // an operator reading a window that starts an hour ago is told why.
        String reported = detail;
        if (covered && row.getCapturedFrom() == null) {
            if (row.getUpdatedAt() != null && row.getCaptureState() != CaptureState.PENDING) {
                reported = "Capture restarted on this node at " + Instant.now() + "; nothing between "
                        + row.getUpdatedAt() + " and then was captured here.";
            }
            row.setCapturedFrom(Instant.now());
        } else if (!covered) {
            row.setCapturedFrom(null);
        }
        row.setCaptureState(newState);
        row.setCaptureDetail(reported);
        row.setUpdatedAt(Instant.now());
        captureNodes.save(row);
    }

    // ---- plumbing --------------------------------------------------------

    /**
     * The nodes to assert a tap on: one per HA pair, and only ones that answered the
     * last scrape.
     *
     * <p>The second filter is not redundant. A node that cannot be reached keeps its
     * last known {@code Active}, so for a while after a failover the pair reports two
     * live members — the dead one and the promoted one — and the pair-reduction would
     * pick the dead one and leave the live node untapped for exactly the interval an
     * operator is watching. A node with a standing error is not serving anything.
     */
    private List<BrokerNodeEntity> servingNodes(UUID clusterId) {
        List<BrokerNodeEntity> all = nodes.findByClusterIdOrderByNameAsc(clusterId);
        List<BrokerNodeEntity> answering =
                all.stream().filter(n -> n.getLastError() == null).toList();
        // If nothing answered, fall back rather than reporting an empty desired state —
        // an empty desired state would look like "remove every tap".
        return ServingNodes.from(answering.isEmpty() ? all : answering);
    }

    private JolokiaBrokerClient client(UUID clusterId, BrokerNodeEntity node) {
        try {
            limiter.acquire(node.getId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE, "Timed out waiting for a per-node call permit.");
        }
        return connections.forCluster(clusterId, node.getJolokiaUrl());
    }

    private static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
