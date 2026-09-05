package io.github.sudoitir.artemisstudio.web.dto;

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

    /** {@code GET /clusters/{id}/metrics}. */
    public record MetricSeriesResponse(
            @Schema(requiredMode = REQUIRED) Instant from,
            @Schema(requiredMode = REQUIRED) Instant to,
            @Schema(requiredMode = REQUIRED) String step,
            @Schema(requiredMode = REQUIRED) boolean truncated,
            @Schema(requiredMode = REQUIRED) List<MetricSeries> series) {}
}
