package io.github.sudoitir.artemisstudio.platform.scrape;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The maintainer's cron that rolls the daily {@code metric_sample} partitions ahead
 * (ADR-0006), runtime-overridable (ADR-0048). How long samples are kept is the
 * {@code metrics} store's retention policy (ADR-0132).
 *
 * <p>Sizing: {@link MetricSampleWriter} appends six rows per queue per tier-B/C tick.
 * ADR-0089 raised that from four by adding {@code deliveringCount} and
 * {@code messagesExpired} — a 50% increase in row volume at the same retention, with no
 * additional broker request. An installation that would rather keep the older footprint
 * trades history for space by lowering the store's retention.
 */
@ConfigurationProperties(prefix = "artemis-studio.metric")
public record MetricProperties(@DefaultValue("0 0 3 * * *") String partitionMaintainerCron) {}
