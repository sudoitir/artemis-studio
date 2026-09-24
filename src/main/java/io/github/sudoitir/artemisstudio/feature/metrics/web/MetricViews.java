package io.github.sudoitir.artemisstudio.feature.metrics.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** The metrics read API (metrics spec, ADR-0033). */
public final class MetricViews {

    private MetricViews() {}

    public record MetricPoint(
            @Schema(requiredMode = REQUIRED) Instant ts,
            @Schema(requiredMode = REQUIRED) double value,
            @Schema(nullable = true) Double peak) {}

    public record MetricSeries(
            @Schema(requiredMode = REQUIRED) String metric,
            @Schema(requiredMode = REQUIRED) String kind,
            @Schema(requiredMode = REQUIRED) String unit,
            @Schema(requiredMode = REQUIRED) List<MetricPoint> points) {}

    /**
     * One broker node's share of a split series (ADR-0110).
     *
     * @param sampled false for a serving node with no sample of the subject in the window: its
     *     series are empty, and that is "not sampled", never zero
     */
    public record MetricNodeSeries(
            @Schema(requiredMode = REQUIRED) String nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) boolean sampled,
            @Schema(requiredMode = REQUIRED) List<MetricSeries> series) {}

    /**
     * {@code GET /clusters/{id}/metrics}.
     *
     * @param series the totals, across every node
     * @param splitBy {@code NODE} when the request asked for a split; null otherwise
     * @param byNode each node's series when split; null otherwise
     */
    public record MetricSeriesResponse(
            @Schema(requiredMode = REQUIRED) Instant from,
            @Schema(requiredMode = REQUIRED) Instant to,
            @Schema(requiredMode = REQUIRED) String step,
            @Schema(requiredMode = REQUIRED) boolean truncated,
            @Schema(requiredMode = REQUIRED) List<MetricSeries> series,
            @Schema(nullable = true) String splitBy,
            @Schema(nullable = true) List<MetricNodeSeries> byNode) {

        public MetricSeriesResponse(
                Instant from, Instant to, String step, boolean truncated, List<MetricSeries> series) {
            this(from, to, step, truncated, series, null, null);
        }
    }
}
