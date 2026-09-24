package io.github.sudoitir.artemisstudio.feature.metrics;

import io.github.sudoitir.artemisstudio.feature.metrics.web.MetricViews.MetricNodeSeries;
import io.github.sudoitir.artemisstudio.feature.metrics.web.MetricViews.MetricPoint;
import io.github.sudoitir.artemisstudio.feature.metrics.web.MetricViews.MetricSeries;
import io.github.sudoitir.artemisstudio.feature.metrics.web.MetricViews.MetricSeriesResponse;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import io.github.sudoitir.artemisstudio.platform.scrape.MetricSampleReaper;
import io.github.sudoitir.artemisstudio.platform.scrape.MetricSamples;
import io.github.sudoitir.artemisstudio.platform.scrape.MetricSamples.Bucket;
import io.github.sudoitir.artemisstudio.platform.scrape.MetricSamples.NodeBucket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Turns a raw metric query into bucketed, server-clamped series (metrics spec,
 * ADR-0033). Every metric name Studio samples is intrinsically either a gauge or a
 * monotonic counter — a fixed lookup here, not a per-row flag, since
 * {@code MetricSampleWriter} never mixes the two under one name.
 */
@Service
public class MetricQueryService {

    private static final Set<String> GAUGE_METRICS = Set.of("messageCount", "consumerCount", "deliveringCount");
    private static final Set<String> RATE_METRICS = Set.of("messagesAdded", "messagesAcked", "messagesExpired");

    /** No bucket finer than the fastest tier that samples metrics (tier B, 15s). */
    private static final Duration MIN_STEP = Duration.ofSeconds(15);

    private static final int MAX_POINTS = 500;

    /** A split by node draws at most this many nodes (ADR-0110). */
    static final int MAX_SPLIT_NODES = 16;

    /** ...and at most this many points per metric across all of them. */
    static final int MAX_SPLIT_POINTS = 2_000;

    /** The one split there is. */
    public static final String SPLIT_BY_NODE = "NODE";

    private final MetricSamples repository;
    private final MetricSampleReaper reaper;
    private final ClusterAccessGuard clusterAccess;
    private final ClusterDirectory directory;

    public MetricQueryService(
            MetricSamples repository,
            MetricSampleReaper reaper,
            ClusterAccessGuard clusterAccess,
            ClusterDirectory directory) {
        this.repository = repository;
        this.reaper = reaper;
        this.clusterAccess = clusterAccess;
        this.directory = directory;
    }

    /** A query without a split: the totals only. */
    public MetricSeriesResponse query(
            java.util.UUID clusterId,
            List<String> metrics,
            String subjectType,
            String subject,
            Instant from,
            Instant to,
            Duration requestedStep) {
        return query(clusterId, metrics, subjectType, subject, from, to, requestedStep, null);
    }

    public MetricSeriesResponse query(
            java.util.UUID clusterId,
            List<String> metrics,
            String subjectType,
            String subject,
            Instant from,
            Instant to,
            Duration requestedStep,
            String splitBy) {
        // Before input validation, so a caller with no grant cannot use the
        // difference between a 400 and a 404 to probe which clusters exist.
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        if (metrics.isEmpty() || metrics.size() > 4) {
            throw new IllegalArgumentException("metric must list between 1 and 4 metric names");
        }
        for (String m : metrics) {
            if (!GAUGE_METRICS.contains(m) && !RATE_METRICS.contains(m)) {
                throw new IllegalArgumentException("unknown metric: " + m);
            }
        }
        if ("QUEUE".equals(subjectType) && (subject == null || subject.isBlank())) {
            throw new IllegalArgumentException("subject is required when subjectType=QUEUE");
        }
        String subjectName = "QUEUE".equals(subjectType) ? subject : null;
        if (splitBy != null) {
            if (!SPLIT_BY_NODE.equals(splitBy)) {
                throw new IllegalArgumentException("splitBy must be NODE");
            }
            // A cluster-scope gauge is an average across queues and nodes, not a total (ADR-0110 D4):
            // split, it would put that average on screen per node as though it were each node's load.
            if (subjectName == null) {
                throw new IllegalArgumentException("a split by node needs one queue: subjectType=QUEUE and a subject");
            }
        }

        boolean truncated = false;

        Instant retentionFloor = Instant.now().minus(Duration.ofDays(reaper.retentionDays()));
        Instant effectiveFrom = from;
        if (effectiveFrom.isBefore(retentionFloor)) {
            effectiveFrom = retentionFloor;
            truncated = true;
        }

        Duration range = Duration.between(effectiveFrom, to);
        Duration step = requestedStep != null ? requestedStep : Duration.ofMinutes(1);
        if (step.compareTo(MIN_STEP) < 0) {
            step = MIN_STEP;
            truncated = requestedStep != null ? true : truncated;
        }
        long maxPointStep = range.dividedBy(MAX_POINTS).plusSeconds(1).getSeconds();
        if (step.toSeconds() < maxPointStep) {
            step = Duration.ofSeconds(maxPointStep);
            truncated = true;
        }

        List<ClusterNode> nodes = splitBy == null ? List.of() : directory.nodes(clusterId);
        if (splitBy != null) {
            // The step widens until every node's series together stays within the point bound.
            int drawn = Math.max(1, Math.min(nodes.size(), MAX_SPLIT_NODES));
            long splitStep = range.multipliedBy(drawn)
                    .dividedBy(MAX_SPLIT_POINTS)
                    .plusSeconds(1)
                    .getSeconds();
            if (step.toSeconds() < splitStep) {
                step = Duration.ofSeconds(splitStep);
                truncated = true;
            }
        }

        Instant finalFrom = effectiveFrom;
        Duration finalStep = step;
        List<MetricSeries> series = metrics.stream()
                .map(metric -> buildSeries(clusterId, metric, subjectName, finalFrom, to, finalStep))
                .toList();
        if (splitBy == null) {
            return new MetricSeriesResponse(effectiveFrom, to, step.toString(), truncated, series);
        }

        Split split = split(clusterId, metrics, subjectName, finalFrom, to, finalStep, nodes);
        return new MetricSeriesResponse(
                effectiveFrom, to, step.toString(), truncated || split.clamped(), series, SPLIT_BY_NODE, split.nodes());
    }

    private record Split(List<MetricNodeSeries> nodes, boolean clamped) {}

    /**
     * Each node's series of one queue: every node that sampled it in the window, and every serving
     * node that did not — listed as not sampled rather than left out, since an absent node reads as
     * a node with nothing on it.
     */
    private Split split(
            UUID clusterId,
            List<String> metrics,
            String subjectName,
            Instant from,
            Instant to,
            Duration step,
            List<ClusterNode> nodes) {
        Map<UUID, String> names = new HashMap<>();
        Set<UUID> ids = new LinkedHashSet<>();
        for (ClusterNode n : nodes) {
            names.put(n.getId(), n.getName());
            if (Boolean.TRUE.equals(n.getActive())) {
                ids.add(n.getId());
            }
        }
        Map<String, Map<UUID, List<MetricPoint>>> perMetric = new HashMap<>();
        for (String metric : metrics) {
            boolean gauge = GAUGE_METRICS.contains(metric);
            List<NodeBucket> buckets = gauge
                    ? repository.gaugeSeriesByNode(clusterId, metric, subjectName, from, to, step)
                    : repository.rateSeriesByNode(clusterId, metric, subjectName, from, to, step);
            Map<UUID, List<MetricPoint>> byNode = new HashMap<>();
            for (NodeBucket b : buckets) {
                byNode.computeIfAbsent(b.nodeId(), k -> new ArrayList<>())
                        .add(new MetricPoint(b.ts(), b.value(), b.peak()));
                ids.add(b.nodeId());
            }
            perMetric.put(metric, byNode);
        }

        List<MetricNodeSeries> out = new ArrayList<>();
        for (UUID id : ids) {
            boolean sampled = perMetric.values().stream().anyMatch(m -> m.containsKey(id));
            List<MetricSeries> series = metrics.stream()
                    .map(metric -> {
                        boolean gauge = GAUGE_METRICS.contains(metric);
                        return new MetricSeries(
                                metric,
                                gauge ? "GAUGE" : "RATE",
                                gauge ? "count" : "msg/s",
                                perMetric.get(metric).getOrDefault(id, List.of()));
                    })
                    .toList();
            // A node since removed from the cluster still owns its samples; it is named as such.
            String name = names.getOrDefault(id, "removed node " + id.toString().substring(0, 8));
            out.add(new MetricNodeSeries(id.toString(), name, sampled, series));
        }
        out.sort(Comparator.comparing(MetricNodeSeries::nodeName));
        boolean clamped = out.size() > MAX_SPLIT_NODES;
        return new Split(clamped ? List.copyOf(out.subList(0, MAX_SPLIT_NODES)) : out, clamped);
    }

    private MetricSeries buildSeries(
            java.util.UUID clusterId, String metric, String subjectName, Instant from, Instant to, Duration step) {
        boolean isGauge = GAUGE_METRICS.contains(metric);
        List<Bucket> buckets = isGauge
                ? repository.gaugeSeries(clusterId, metric, subjectName, from, to, step)
                : repository.rateSeries(clusterId, metric, subjectName, from, to, step);
        List<MetricPoint> points = buckets.stream()
                .map(b -> new MetricPoint(b.ts(), b.value(), b.peak()))
                .toList();
        return new MetricSeries(metric, isGauge ? "GAUGE" : "RATE", isGauge ? "count" : "msg/s", points);
    }
}
