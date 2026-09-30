package io.github.sudoitir.artemisstudio.platform.scrape;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Scrape cadence and metric partition maintenance (ADR-0015, ADR-0006, ADR-0048). */
@Component
@RequiredArgsConstructor
public class ScrapeSettings implements SettingsContribution {

    public static final String TIER_A = "scrape.tier-a-interval";
    public static final String TIER_B = "scrape.tier-b-interval";
    public static final String TIER_C = "scrape.tier-c-interval";
    public static final String DISCOVERY = "scrape.discovery-interval";
    public static final String METRIC_PARTITION_CRON = "metric.partition-maintainer-cron";

    private static final String GROUP_SCRAPE = "Scrape";
    private static final String GROUP_RETENTION = "Retention";

    private final ScrapeProperties scrape;
    private final MetricProperties metric;

    @Override
    public String featureId() {
        return "scrape";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        TIER_A,
                        GROUP_SCRAPE,
                        "Tier A interval",
                        "HA state, topology and split-brain corroboration.",
                        Kind.DURATION,
                        () -> scrape.tierAInterval().toString(),
                        null),
                new SettingDef(
                        TIER_B,
                        GROUP_SCRAPE,
                        "Tier B interval",
                        "Fast re-read of the queues that were busy last sweep.",
                        Kind.DURATION,
                        () -> scrape.tierBInterval().toString(),
                        null),
                new SettingDef(
                        TIER_C,
                        GROUP_SCRAPE,
                        "Tier C interval",
                        "Full queue sweep, one page per node per tick.",
                        Kind.DURATION,
                        () -> scrape.tierCInterval().toString(),
                        null),
                new SettingDef(
                        DISCOVERY,
                        GROUP_SCRAPE,
                        "Discovery interval",
                        "Re-read each cluster's topology, so a broker that joins appears on its own.",
                        Kind.DURATION,
                        () -> scrape.discoveryInterval().toString(),
                        null),
                new SettingDef(
                        METRIC_PARTITION_CRON,
                        GROUP_RETENTION,
                        "Partition maintainer schedule",
                        "When daily metric partitions are created ahead.",
                        Kind.CRON,
                        metric::partitionMaintainerCron,
                        null));
    }
}
