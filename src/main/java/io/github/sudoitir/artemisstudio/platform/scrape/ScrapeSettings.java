package io.github.sudoitir.artemisstudio.platform.scrape;

import io.github.sudoitir.artemisstudio.kernel.core.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Scrape cadence and metric-sample retention (ADR-0015, ADR-0006, ADR-0048). */
@Component
@RequiredArgsConstructor
public class ScrapeSettings implements SettingsContribution {

    public static final String TIER_A = "scrape.tier-a-interval";
    public static final String TIER_B = "scrape.tier-b-interval";
    public static final String TIER_C = "scrape.tier-c-interval";
    public static final String METRIC_RETENTION_DAYS = "metric.retention-days";
    public static final String METRIC_REAPER_CRON = "metric.reaper-cron";
    public static final String METRIC_PARTITION_CRON = "metric.partition-maintainer-cron";

    private final ArtemisStudioProperties defaults;
    private final MetricSampleReaper reaper;

    @Override
    public String featureId() {
        return "scrape";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        TIER_A,
                        "Scrape",
                        "Tier A interval",
                        "HA state, topology and split-brain corroboration.",
                        Kind.DURATION,
                        () -> defaults.scrape().tierAInterval().toString(),
                        null),
                new SettingDef(
                        TIER_B,
                        "Scrape",
                        "Tier B interval",
                        "Fast re-read of the queues that were busy last sweep.",
                        Kind.DURATION,
                        () -> defaults.scrape().tierBInterval().toString(),
                        null),
                new SettingDef(
                        TIER_C,
                        "Scrape",
                        "Tier C interval",
                        "Full queue sweep, one page per node per tick.",
                        Kind.DURATION,
                        () -> defaults.scrape().tierCInterval().toString(),
                        null),
                new SettingDef(
                        METRIC_RETENTION_DAYS,
                        "Retention",
                        "Metric retention (days)",
                        "Raw metric_sample rows older than this are trimmed.",
                        Kind.INT,
                        () -> Integer.toString(defaults.metric().retentionDays()),
                        s -> reaper.setRetentionDays(s.intValue(METRIC_RETENTION_DAYS))),
                new SettingDef(
                        METRIC_REAPER_CRON,
                        "Retention",
                        "Metric reaper schedule",
                        "When the metric trim runs. Six-field cron.",
                        Kind.CRON,
                        () -> defaults.metric().reaperCron(),
                        null),
                new SettingDef(
                        METRIC_PARTITION_CRON,
                        "Retention",
                        "Partition maintainer schedule",
                        "When daily metric partitions are created ahead and expired ones dropped.",
                        Kind.CRON,
                        () -> defaults.metric().partitionMaintainerCron(),
                        null));
    }
}
