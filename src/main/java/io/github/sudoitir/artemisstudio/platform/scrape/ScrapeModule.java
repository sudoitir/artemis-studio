package io.github.sudoitir.artemisstudio.platform.scrape;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.TopicDef;

/** Tiered broker polling, queue snapshots and metric samples. Module descriptor (ADR-0070). */
public final class ScrapeModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("scrape")
            .title("Scraping")
            .kind(FeatureDescriptor.Kind.PLATFORM)
            .required(true)
            .settingKey(ScrapeSettings.TIER_A)
            .settingKey(ScrapeSettings.TIER_B)
            .settingKey(ScrapeSettings.TIER_C)
            .settingKey(ScrapeSettings.METRIC_RETENTION_DAYS)
            .settingKey(ScrapeSettings.METRIC_REAPER_CRON)
            .settingKey(ScrapeSettings.METRIC_PARTITION_CRON)
            .streamTopic(TopicDef.signal("queues"))
            .build();

    private ScrapeModule() {}
}
