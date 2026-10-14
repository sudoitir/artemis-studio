package io.github.sudoitir.artemisstudio.platform.scrape;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.partition.PartitionSpec;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The scrape tiers (ADR-0015) as jobs on the one runner (ADR-0194), each with the cadence Settings holds
 * now, re-read after every run (ADR-0025).
 */
@Configuration(proxyBeanMethods = false)
class ScrapeJobs {

    private static final String FEATURE = "scrape";

    @Bean
    ScheduledJob scrapeTierAJob(ScrapeScheduler scheduler, SettingsService settings) {
        return ScheduledJob.fixedDelay(
                "scrape-tier-a",
                FEATURE,
                ScheduledJob.Scope.INSTANCE,
                () -> settings.duration(ScrapeSettings.TIER_A),
                scheduler::tierA);
    }

    @Bean
    ScheduledJob scrapeTierBJob(ScrapeScheduler scheduler, SettingsService settings) {
        return ScheduledJob.fixedDelay(
                "scrape-tier-b",
                FEATURE,
                ScheduledJob.Scope.INSTANCE,
                () -> settings.duration(ScrapeSettings.TIER_B),
                scheduler::tierB);
    }

    @Bean
    ScheduledJob scrapeTierCJob(ScrapeScheduler scheduler, SettingsService settings) {
        return ScheduledJob.fixedDelay(
                "scrape-tier-c",
                FEATURE,
                ScheduledJob.Scope.INSTALLATION,
                () -> settings.duration(ScrapeSettings.TIER_C),
                scheduler::tierC);
    }

    @Bean
    ScheduledJob scrapeDiscoveryJob(ScrapeScheduler scheduler, SettingsService settings) {
        return ScheduledJob.fixedDelay(
                "scrape-discovery",
                FEATURE,
                ScheduledJob.Scope.INSTALLATION,
                () -> settings.duration(ScrapeSettings.DISCOVERY),
                scheduler::discovery);
    }

    @Bean
    PartitionSpec metricSamplePartitions() {
        return MetricSampleStore.PARTITIONS;
    }
}
