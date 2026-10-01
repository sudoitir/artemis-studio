package io.github.sudoitir.artemisstudio.platform.scrape;

import io.github.sudoitir.artemisstudio.kernel.jobs.JobStatuses;
import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerListOps;
import io.github.sudoitir.artemisstudio.platform.broker.CoreSubscriptionManager;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaResponse;
import io.github.sudoitir.artemisstudio.platform.broker.NodeEndpoint;
import io.github.sudoitir.artemisstudio.platform.broker.QueueRow;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDutyAcquired;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDutyReleased;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterOwnership;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterService;
import io.github.sudoitir.artemisstudio.platform.clusters.NodeStateRecorder;
import io.github.sudoitir.artemisstudio.platform.clusters.RegisteredCluster;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The tiered scrape scheduler (ADR-0015). Retires {@code HaRefreshTask}.
 *
 * <ul>
 *   <li><b>Tier A</b> (~5s): one HA-attribute read per manageable node, each on its own
 *       so a stalled node delays only itself, then a per-cluster split-brain corroboration
 *       pass. It is the only tier that decides whether a node is reachable.
 *   <li><b>Tier B</b> (~15s): the first {@code listQueues} page per node, so the
 *       busiest queues get a fast refresh without waiting for the full sweep.
 *   <li><b>Tier C</b> (~5m): one {@code listQueues} page per node per tick,
 *       walking the whole set over several ticks, then reaping rows the sweep
 *       did not touch.
 *   <li><b>Discovery</b> (~1m): re-run topology discovery per cluster, so a broker that
 *       joined after registration appears without anyone asking (ADR-0119).
 * </ul>
 *
 * <p>Every tier: acquire a per-node permit, do the POST, parse, hand a plain
 * result to a short transaction. Per-node fan-out is on virtual threads so one
 * slow or unreachable broker never blocks its siblings or another cluster. A tier A
 * read that fails to reach the broker lands on {@code broker_node.last_error}; a failure
 * of any other job is logged and leaves the node's reachability as it was.
 */
// ponytail: tier B just refreshes listQueues page 1. Artemis 2.44 sortColumn /
// GREATER_THAN both 500 with an NPE (Slice 0), so a broker-sorted "hot page" is
// not fetchable — tier C is the coverage guarantee, tier B is best-effort speed.
@Component
@DependsOn("settingsService")
@RequiredArgsConstructor
@Slf4j
public class ScrapeScheduler implements SmartInitializingSingleton, DisposableBean {

    private static final String FEATURE = "scrape";

    /**
     * How long a tier A pass waits for its probes before it carries on without the ones still
     * running. A node that has not answered by then is left to finish on its own and is not probed
     * again until it does.
     */
    private static final Duration PROBE_GRACE = Duration.ofSeconds(1);

    /** Why each node's job last failed, by node and job, so a node that keeps failing the same way is logged once. */
    private final Map<String, String> lastFailures = new ConcurrentHashMap<>();

    /** The nodes a tier A probe is running for on this replica. */
    private final Set<UUID> probesInFlight = ConcurrentHashMap.newKeySet();

    /** Where tier A probes run: one virtual thread each, so a stalled node holds nothing but its own. */
    private final ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Schedules the tiers as trigger tasks whose {@code nextExecution} re-reads
     * {@link SettingsService} every fire (ADR-0025), so a cadence change in Settings applies
     * without a restart. Replaces the SpEL-bound
     * {@code @Scheduled(fixedDelayString = "#{@settingsService…}")} that resolved once at wiring
     * time. Fixed-delay semantics are preserved: the trigger reads {@code lastCompletion} (falling
     * back to {@code lastActualExecution}, then "now") and adds the current interval. They run on
     * a pool of their own, never the one other scheduled jobs share, so broker I/O never delays
     * them and they never delay it.
     */
    @Override
    public void afterSingletonsInstantiated() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        tierScheduler = scheduler;
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("scrape-");
        scheduler.initialize();

        for (ScheduledJob tier : List.of(
                ScheduledJob.fixedDelay(
                        "scrape-tier-a",
                        FEATURE,
                        ScheduledJob.Scope.INSTANCE,
                        () -> settings.duration(ScrapeSettings.TIER_A),
                        this::tierA),
                ScheduledJob.fixedDelay(
                        "scrape-tier-b",
                        FEATURE,
                        ScheduledJob.Scope.INSTANCE,
                        () -> settings.duration(ScrapeSettings.TIER_B),
                        this::tierB),
                ScheduledJob.fixedDelay(
                        "scrape-tier-c",
                        FEATURE,
                        ScheduledJob.Scope.INSTALLATION,
                        () -> settings.duration(ScrapeSettings.TIER_C),
                        this::tierC),
                ScheduledJob.fixedDelay(
                        "scrape-discovery",
                        FEATURE,
                        ScheduledJob.Scope.INSTALLATION,
                        () -> settings.duration(ScrapeSettings.DISCOVERY),
                        this::discovery))) {
            scheduler.schedule(jobStatuses.instrument(tier), jobStatuses.trigger(tier));
        }
    }

    private static final String[] HA_ATTRS = {
        "Active", "Started", "Backup", "ReplicaSync", "NodeID", "Clustered", "Version"
    };
    private static final String LIST_QUEUES = "listQueues(java.lang.String,int,int)";
    private static final int PAGE_SIZE = 200;

    private final io.github.sudoitir.artemisstudio.kernel.settings.SettingsService settings;
    private final ClusterDirectory clusters;
    private final ClusterOwnership ownership;
    private final ClusterService clusterService;
    private final BrokerConnections connections;
    private final ScrapeCycle scrapeCycle;
    private final NodeStateRecorder persist;
    private final SweepCursor sweepCursor;
    private final QueueSnapshotUpsert upsert;
    private final MetricSampleWriter metrics;
    private final StreamSignals streamSignals;
    private final CoreSubscriptionManager coreSubscriptions;
    private final ApplicationEventPublisher eventPublisher;
    private final JobStatuses jobStatuses;

    /** The tiers' own pool, built when tasks are configured. */
    private volatile ThreadPoolTaskScheduler tierScheduler;

    private record QueuesPage(List<QueueRow> rows, long count) {}

    // ---- tiers -------------------------------------------------------------

    /** Pause the tiers. A tier already running finishes; the broker-call gate refuses what it starts. */
    public void stopTiers() {
        ThreadPoolTaskScheduler scheduler = tierScheduler;
        if (scheduler != null) {
            scheduler.stop();
        }
    }

    /** Resume the tiers after a stopped context has been started. */
    public void startTiers() {
        ThreadPoolTaskScheduler scheduler = tierScheduler;
        if (scheduler != null) {
            scheduler.start();
        }
    }

    /** The pool is not a bean, so nothing else ends its threads when the context closes. */
    @Override
    public void destroy() {
        probes.shutdownNow();
        ThreadPoolTaskScheduler scheduler = tierScheduler;
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /** Tiers A and B visit the clusters this replica owns; C and discovery run once for all (ShedLock). */
    public void tierA() {
        for (RegisteredCluster cluster : clusters.owned()) {
            tierA(cluster.getId());
        }
    }

    /**
     * One tier A pass for one cluster. Each node is probed on its own and never twice at once, so a
     * node that stalls is skipped until it answers while its siblings are probed every pass. Taking a
     * cluster over runs a pass at once, which can overlap the scheduled one.
     */
    private void tierA(UUID clusterId) {
        long cycle = scrapeCycle.next(clusterId);
        List<Future<?>> started = manageableNodes(clusterId).stream()
                .filter(node -> probesInFlight.add(node.getId()))
                .<Future<?>>map(node -> probes.submit(() -> {
                    try {
                        runIsolated(node, "HA read", n -> probe(clusterId, n, cycle));
                    } finally {
                        probesInFlight.remove(node.getId());
                    }
                }))
                .toList();
        awaitProbes(started);
        try {
            List<NodeEndpoint> endpoints = persist.endpoints(clusterId);
            scrapeCycle.corroborate(clusterId, endpoints);
            streamSignals.afterTierA(clusterId, endpoints);
            // Follow failover: reconcile Core notification subscriptions against
            // who is live now. Never in a transaction, on this virtual-thread pool.
            coreSubscriptions.reconcile(clusterId, endpoints);
            if (!ownership.owns(clusterId)) {
                // Ownership moved while this pass ran, after the release had already dropped its state.
                coreSubscriptions.forget(clusterId);
            }
        } catch (RuntimeException e) {
            log.warn("Split-brain corroboration failed for cluster {}: {}", clusterId, e.toString());
        }
        eventPublisher.publishEvent(new ScrapeTierCompleted(clusterId, ScrapeTierCompleted.Tier.A));
    }

    private static void awaitProbes(List<Future<?>> started) {
        long deadline = System.nanoTime() + PROBE_GRACE.toNanos();
        for (Future<?> probe : started) {
            try {
                probe.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException _) {
                // Still running: it lands its own result when it answers.
            } catch (ExecutionException e) {
                log.warn("Scrape task failed unexpectedly: {}", e.getCause() != null ? e.getCause() : e, e);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** A cluster just became this replica's: scrape it now, so its state and subscriptions do not wait a tick. */
    @EventListener
    void onDutyAcquired(ClusterDutyAcquired acquired) {
        Thread.startVirtualThread(() -> {
            try {
                tierA(acquired.clusterId());
            } catch (RuntimeException e) {
                log.warn(
                        "First scrape of cluster {} after taking it over failed: {}",
                        acquired.clusterId(),
                        e.toString());
            }
        });
    }

    /**
     * A cluster stopped being this replica's: drop what it holds for it, namely Core notification
     * subscriptions (the new owner subscribes, and two would insert every event twice), the scrape
     * cycle and its corroboration ratchet, and the stream signatures. It costs the new owner one
     * extra cycle, the same as a restart.
     */
    @EventListener
    void onDutyReleased(ClusterDutyReleased released) {
        UUID clusterId = released.clusterId();
        scrapeCycle.forget(clusterId);
        streamSignals.forget(clusterId);
        Thread.startVirtualThread(() -> coreSubscriptions.forget(clusterId));
    }

    public void tierB() {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (RegisteredCluster cluster : clusters.owned()) {
                UUID clusterId = cluster.getId();
                fanOut(pool, manageableNodes(clusterId), "hot queue read", node -> scrapeHotQueues(clusterId, node));
                eventPublisher.publishEvent(new ScrapeTierCompleted(clusterId, ScrapeTierCompleted.Tier.B));
            }
        }
    }

    public void tierC() {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (RegisteredCluster cluster : clusters.clusters()) {
                UUID clusterId = cluster.getId();
                fanOut(pool, manageableNodes(clusterId), "queue sweep", node -> scrapeSweepPage(clusterId, node));
                eventPublisher.publishEvent(new ScrapeTierCompleted(clusterId, ScrapeTierCompleted.Tier.C));
            }
        }
    }

    /** One cluster per virtual thread, so a slow seed on one cluster never delays another's. */
    public void discovery() {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (RegisteredCluster cluster : clusters.clusters()) {
                UUID clusterId = cluster.getId();
                pool.submit(() -> {
                    try {
                        clusterService.rediscover(clusterId);
                    } catch (RuntimeException e) {
                        log.warn("Topology discovery failed for cluster {}: {}", clusterId, e.toString());
                    }
                });
            }
        }
    }

    // ---- per-node jobs ---------------------------------------------------

    /**
     * The reachability probe, and the only job that writes or clears {@code broker_node.last_error}.
     * A read that never got an answer marks the node; an answer that carries an error is a failed
     * observation, so the node keeps the state and the error it had.
     */
    private void probe(UUID clusterId, ClusterNode node, long cycle) {
        JolokiaResponse ha;
        try {
            ha = connections.forCluster(clusterId, node.getJolokiaUrl()).readBrokerAttributes(HA_ATTRS);
        } catch (RuntimeException e) {
            String message =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            persist.recordNodeError(node.getId(), message);
            throw e;
        }
        if (!ha.ok()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.BAD_RESPONSE, "The HA read answered with an error: " + ha.failure());
        }
        persist.applyTierA(node.getId(), ha.value(), cycle);
    }

    private void scrapeHotQueues(UUID clusterId, ClusterNode node) {
        QueuesPage page = listQueues(clusterId, node, BrokerListOps.ALL, 1, PAGE_SIZE);
        upsert.upsertBatch(page.rows());
        metrics.appendQueueSamples(page.rows());
        streamSignals.afterQueueScrape(clusterId, page.rows());
    }

    private void scrapeSweepPage(UUID clusterId, ClusterNode node) {
        int pageNo = sweepCursor.nextPage(node.getId());
        Instant sweepStart = sweepCursor.sweepStart(node.getId());

        QueuesPage page = listQueues(clusterId, node, BrokerListOps.ALL, pageNo, PAGE_SIZE);
        upsert.upsertBatch(page.rows());
        metrics.appendQueueSamples(page.rows());
        streamSignals.afterQueueScrape(clusterId, page.rows());

        boolean lastPage = (long) pageNo * PAGE_SIZE >= page.count();
        if (lastPage) {
            int reaped = upsert.reapStale(node.getId(), sweepStart);
            if (reaped > 0) {
                log.info("Sweep of {} complete: reaped {} stale queue rows", node.getName(), reaped);
            }
        }
        sweepCursor.advance(node.getId(), lastPage);
    }

    // ---- plumbing -------------------------------------------------------

    private QueuesPage listQueues(UUID clusterId, ClusterNode node, String options, int page, int size) {
        JolokiaBrokerClient client = connections.forCluster(clusterId, node.getJolokiaUrl());
        JsonNode env = client.execOnBrokerParsed(LIST_QUEUES, options, page, size);
        List<QueueRow> rows = QueueRow.parsePage(env == null ? null : env.get("data"), clusterId, node.getId());
        long count = env == null ? rows.size() : env.path("count").asLong(rows.size());
        return new QueuesPage(rows, count);
    }

    private List<ClusterNode> manageableNodes(UUID clusterId) {
        return clusters.nodes(clusterId).stream()
                .filter(n -> n.getJolokiaUrl() != null)
                .toList();
    }

    private void fanOut(ExecutorService pool, List<ClusterNode> targets, String jobName, NodeJob job) {
        List<Future<?>> futures = targets.stream()
                .<Future<?>>map(node -> pool.submit(() -> runIsolated(node, jobName, job)))
                .toList();
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                log.warn("Scrape task failed unexpectedly: {}", e.getCause() != null ? e.getCause() : e, e);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void runIsolated(ClusterNode node, String jobName, NodeJob job) {
        String key = node.getId() + "/" + jobName;
        try {
            // The client waits for the node's ceiling before each request it sends (ADR-0076).
            job.run(node);
            lastFailures.remove(key);
        } catch (RuntimeException e) {
            // Only the tier A probe says whether a node is reachable; what failed here is not that.
            Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
            String why = root == e ? e.toString() : e + ", caused by " + root;
            if (!why.equals(lastFailures.put(key, why))) {
                log.warn("The {} of {} ({}) failed: {}", jobName, node.getName(), node.getJolokiaUrl(), why);
            }
        }
    }

    @FunctionalInterface
    private interface NodeJob {
        void run(ClusterNode node);
    }
}
