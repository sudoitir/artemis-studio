package io.github.sudoitir.artemisstudio.platform.scrape;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import io.github.sudoitir.artemisstudio.kernel.core.ShutdownStep;
import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ScrapeJobs {

    @Bean
    ShutdownStep scrapeShutdown(ScrapeScheduler scheduler) {
        return new ShutdownStep("scrape", ShutdownPhases.BROKER_CALLS, scheduler::stopTiers, scheduler::startTiers);
    }

    @Bean
    ScheduledJob metricReaperJob(MetricSampleReaper reaper, SettingsService settings) {
        return ScheduledJob.cron(
                "metric-reaper", "scrape", () -> settings.value(ScrapeSettings.METRIC_REAPER_CRON), reaper::reap);
    }

    @Bean
    ScheduledJob metricPartitionJob(MetricPartitionMaintainer maintainer, SettingsService settings) {
        return ScheduledJob.cron(
                "metric-partitions",
                "scrape",
                () -> settings.value(ScrapeSettings.METRIC_PARTITION_CRON),
                maintainer::maintain);
    }
}
