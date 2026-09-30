package io.github.sudoitir.artemisstudio.platform.scrape;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import io.github.sudoitir.artemisstudio.kernel.core.ShutdownStep;
import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ScrapeJobs {

    private static final String FEATURE = "scrape";

    @Bean
    ShutdownStep scrapeShutdown(ScrapeScheduler scheduler) {
        return new ShutdownStep(FEATURE, ShutdownPhases.BROKER_CALLS, scheduler::stopTiers, scheduler::startTiers);
    }

    @Bean
    ScheduledJob metricPartitionJob(MetricPartitionMaintainer maintainer, SettingsService settings) {
        return ScheduledJob.cron(
                "metric-partitions",
                FEATURE,
                ScheduledJob.Scope.INSTALLATION,
                () -> settings.value(ScrapeSettings.METRIC_PARTITION_CRON),
                maintainer::maintain);
    }
}
