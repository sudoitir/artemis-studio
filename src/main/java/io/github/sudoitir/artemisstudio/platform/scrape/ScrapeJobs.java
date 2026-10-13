package io.github.sudoitir.artemisstudio.platform.scrape;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import io.github.sudoitir.artemisstudio.kernel.core.ShutdownStep;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.partition.PartitionSpec;
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
    PartitionSpec metricSamplePartitions() {
        return MetricSampleStore.PARTITIONS;
    }
}
