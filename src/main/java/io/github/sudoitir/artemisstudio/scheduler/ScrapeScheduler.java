package io.github.sudoitir.artemisstudio.scheduler;

import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.QueueRow;
import io.github.sudoitir.artemisstudio.domain.topology.NodeEndpoint;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.persist.MetricSampleWriter;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotUpsert;
import io.github.sudoitir.artemisstudio.sse.StreamSignals;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The tiered scrape scheduler (ADR-0015). Retires {@code HaRefreshTask}.
 *
 * <ul>
 *   <li><b>Tier A</b> (~5s): one HA-attribute read per manageable node, then a
 *       per-cluster split-brain corroboration pass.
 *   <li><b>Tier B</b> (~15s): the first {@code listQueues} page per node, so the
 *       busiest queues get a fast refresh without waiting for the full sweep.
 *   <li><b>Tier C</b> (~5m): one {@code listQueues} page per node per tick,
 *       walking the whole set over several ticks, then reaping rows the sweep
 *       did not touch.
 * </ul>
 *
 * <p>Every tier: acquire a per-node permit, do the POST, parse, hand a plain
 * result to a short transaction. Per-node fan-out is on virtual threads so one
 * slow or unreachable broker never blocks its siblings or another cluster — its
 * failure lands on {@code broker_node.last_error} and the loop carries on.
 */
// ponytail: tier B just refreshes listQueues page 1. Artemis 2.44 sortColumn /
// GREATER_THAN both 500 with an NPE (Slice 0), so a broker-sorted "hot page" is
// not fetchable — tier C is the coverage guarantee, tier B is best-effort speed.
@Component
@DependsOn("settingsService")
@RequiredArgsConstructor
@Slf4j
public class ScrapeScheduler implements SchedulingConfigurer {

    /**
     * Registers the three tiers as trigger tasks whose {@code nextExecution}
     * re-reads {@link SettingsService} every fire (ADR-0025), so a cadence change
     * in Settings applies without a restart. Replaces the SpEL-bound
     * {@code @Scheduled(fixedDelayString = "#{@settingsService…}")} that resolved
     * once at wiring time. Fixed-delay semantics are preserved: the trigger reads
     * {@code lastCompletion} (falling back to {@code lastActualExecution}, then
     * "now") and adds the current interval.
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("scrape-");
        scheduler.initialize();
        registrar.setTaskScheduler(scheduler);

        registrar.addTriggerTask(this::tierA, fixedDelay(settings::tierA));
        registrar.addTriggerTask(this::tierB, fixedDelay(settings::tierB));
        registrar.addTriggerTask(this::tierC, fixedDelay(settings::tierC));
    }

    static Trigger fixedDelay(java.util.function.Supplier<Duration> interval) {
        return context -> {
            Instant last = context.lastCompletion() != null
                    ? context.lastCompletion()
                    : context.lastActualExecution() != null ? context.lastActualExecution() : Instant.now();
            return last.plus(interval.get());
        };
    }

    private static final String[] HA_ATTRS = {
        "Active", "Started", "Backup", "ReplicaSync", "NodeID", "Clustered", "Version"
    };
    private static final String LIST_QUEUES = "listQueues(java.lang.String,int,int)";
    private static final int PAGE_SIZE = 200;

    private final io.github.sudoitir.artemisstudio.service.SettingsService settings;
    private final ClusterRepository clusters;
    private final BrokerNodeRepository nodes;
    private final BrokerConnections connections;
    private final NodeCallLimiter limiter;
    private final ScrapeCycle scrapeCycle;
    private final ScrapePersistence persist;
    private final SweepCursor sweepCursor;
    private final QueueSnapshotUpsert upsert;
    private final MetricSampleWriter metrics;
    private final StreamSignals streamSignals;

    private record QueuesPage(List<QueueRow> rows, long count) {}

    // ---- tiers -------------------------------------------------------------

    public void tierA() {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ClusterEntity cluster : clusters.findAll()) {
                UUID clusterId = cluster.getId();
                long cycle = scrapeCycle.next(clusterId);
                fanOut(pool, manageableNodes(clusterId), node -> scrapeTierA(clusterId, node, cycle));
                try {
                    List<NodeEndpoint> endpoints = persist.endpoints(clusterId);
                    scrapeCycle.corroborate(clusterId, endpoints);
                    streamSignals.afterTierA(clusterId, endpoints);
                } catch (RuntimeException e) {
                    log.warn("Split-brain corroboration failed for cluster {}: {}", clusterId, e.toString());
                }
            }
        }
    }

    public void tierB() {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ClusterEntity cluster : clusters.findAll()) {
                UUID clusterId = cluster.getId();
                fanOut(pool, manageableNodes(clusterId), node -> scrapeHotQueues(clusterId, node));
            }
        }
    }

    public void tierC() {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ClusterEntity cluster : clusters.findAll()) {
                UUID clusterId = cluster.getId();
                fanOut(pool, manageableNodes(clusterId), node -> scrapeSweepPage(clusterId, node));
            }
        }
    }

    // ---- per-node jobs ---------------------------------------------------

    private void scrapeTierA(UUID clusterId, BrokerNodeEntity node, long cycle) {
        JolokiaBrokerClient client = connections.forCluster(clusterId, node.getJolokiaUrl());
        JsonNode ha = client.readBrokerAttributes(HA_ATTRS).value();
        persist.applyTierA(node.getId(), ha, cycle);
    }

    private void scrapeHotQueues(UUID clusterId, BrokerNodeEntity node) {
        QueuesPage page = listQueues(clusterId, node, "", 1, PAGE_SIZE);
        upsert.upsertBatch(page.rows());
        metrics.appendQueueSamples(page.rows());
        streamSignals.afterQueueScrape(clusterId, page.rows());
    }

    private void scrapeSweepPage(UUID clusterId, BrokerNodeEntity node) {
        int pageNo = sweepCursor.nextPage(node.getId());
        Instant sweepStart = sweepCursor.sweepStart(node.getId());

        QueuesPage page = listQueues(clusterId, node, "", pageNo, PAGE_SIZE);
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

    private QueuesPage listQueues(UUID clusterId, BrokerNodeEntity node, String options, int page, int size) {
        JolokiaBrokerClient client = connections.forCluster(clusterId, node.getJolokiaUrl());
        JsonNode env = client.execOnBrokerParsed(LIST_QUEUES, options, page, size);
        List<QueueRow> rows = QueueRow.parsePage(env == null ? null : env.get("data"), clusterId, node.getId());
        long count = env == null ? rows.size() : env.path("count").asLong(rows.size());
        return new QueuesPage(rows, count);
    }

    private List<BrokerNodeEntity> manageableNodes(UUID clusterId) {
        return nodes.findByClusterIdOrderByNameAsc(clusterId).stream()
                .filter(n -> n.getJolokiaUrl() != null)
                .toList();
    }

    private void fanOut(ExecutorService pool, List<BrokerNodeEntity> targets, NodeJob job) {
        List<Future<?>> futures = new ArrayList<>();
        for (BrokerNodeEntity node : targets) {
            futures.add(pool.submit(() -> runIsolated(node, job)));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                log.warn("Scrape task failed unexpectedly: {}", e.getCause() != null ? e.getCause() : e, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void runIsolated(BrokerNodeEntity node, NodeJob job) {
        try {
            limiter.acquire(node.getId());
            job.run(node);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            String message =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Scrape failed for {} ({}): {}", node.getName(), node.getJolokiaUrl(), message);
            persist.recordNodeError(node.getId(), message);
        }
    }

    @FunctionalInterface
    private interface NodeJob {
        void run(BrokerNodeEntity node);
    }
}
