package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.service.MetricQueryService;
import io.github.sudoitir.artemisstudio.web.dto.MetricViews.MetricSeriesResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Historical metric reads (metrics spec, ADR-0033). */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MetricQueryService metricQuery;

    @GetMapping
    public MetricSeriesResponse metrics(
            @PathVariable UUID clusterId,
            @RequestParam List<String> metric,
            @RequestParam(defaultValue = "CLUSTER") String subjectType,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String step) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(Duration.ofHours(1));
        Duration requestedStep = step != null ? Duration.parse(step) : null;
        return metricQuery.query(clusterId, metric, subjectType, subject, effectiveFrom, effectiveTo, requestedStep);
    }
}
