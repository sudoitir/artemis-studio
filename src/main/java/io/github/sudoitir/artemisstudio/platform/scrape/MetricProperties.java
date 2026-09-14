package io.github.sudoitir.artemisstudio.platform.scrape;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Raw {@code metric_sample} retention (ADR-0006 — 7-day default). The nightly reaper
 * trims older rows and the maintainer rolls the daily partitions; both crons are
 * runtime-overridable (ADR-0048).
 */
@ConfigurationProperties(prefix = "artemis-studio.metric")
public record MetricProperties(
        @DefaultValue("7") int retentionDays,
        @DefaultValue("0 30 3 * * *") String reaperCron,
        @DefaultValue("0 0 3 * * *") String partitionMaintainerCron) {}
