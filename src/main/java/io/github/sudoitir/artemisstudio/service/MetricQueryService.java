package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.persist.MetricSampleReaper;
import io.github.sudoitir.artemisstudio.persist.MetricSeriesRepository;
import io.github.sudoitir.artemisstudio.persist.MetricSeriesRepository.Bucket;
import io.github.sudoitir.artemisstudio.web.dto.MetricViews.MetricPoint;
import io.github.sudoitir.artemisstudio.web.dto.MetricViews.MetricSeries;
import io.github.sudoitir.artemisstudio.web.dto.MetricViews.MetricSeriesResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Turns a raw metric query into bucketed, server-clamped series (metrics spec,
 * ADR-0033). Every metric name Studio samples is intrinsically either a gauge or a
 * monotonic counter — a fixed lookup here, not a per-row flag, since
 * {@code MetricSampleWriter} never mixes the two under one name.
 */
@Service
public class MetricQueryService {

    private static final Set<String> GAUGE_METRICS = Set.of("messageCount", "consumerCount");
    private static final Set<String> RATE_METRICS = Set.of("messagesAdded", "messagesAcked");

    /** No bucket finer than the fastest tier that samples metrics (tier B, 15s). */
    private static final Duration MIN_STEP = Duration.ofSeconds(15);

    private static final int MAX_POINTS = 500;

    private final MetricSeriesRepository repository;
    private final MetricSampleReaper reaper;

    public MetricQueryService(MetricSeriesRepository repository, MetricSampleReaper reaper) {
        this.repository = repository;
        this.reaper = reaper;
    }

    public MetricSeriesResponse query(
            java.util.UUID clusterId,
            List<String> metrics,
            String subjectType,
            String subject,
            Instant from,
            Instant to,
            Duration requestedStep) {
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

        Instant finalFrom = effectiveFrom;
        Duration finalStep = step;
        List<MetricSeries> series = metrics.stream()
                .map(metric -> buildSeries(clusterId, metric, subjectName, finalFrom, to, finalStep))
                .toList();

        return new MetricSeriesResponse(effectiveFrom, to, step.toString(), truncated, series);
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
