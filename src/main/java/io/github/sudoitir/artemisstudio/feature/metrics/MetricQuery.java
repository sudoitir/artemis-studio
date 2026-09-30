package io.github.sudoitir.artemisstudio.feature.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One metric-series read.
 *
 * @param metrics the metric names, between 1 and 4
 * @param subjectType {@code CLUSTER} or {@code QUEUE}
 * @param subject the queue name when {@code subjectType} is {@code QUEUE}
 * @param from start of the range
 * @param to end of the range
 * @param requestedStep the bucket width asked for, or null for the server's choice
 * @param splitBy {@link MetricQueryService#SPLIT_BY_NODE} for a per-node breakdown, or null for totals only
 */
public record MetricQuery(
        List<String> metrics,
        String subjectType,
        String subject,
        Instant from,
        Instant to,
        Duration requestedStep,
        String splitBy) {}
