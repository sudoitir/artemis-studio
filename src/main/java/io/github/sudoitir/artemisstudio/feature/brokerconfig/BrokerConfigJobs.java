package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class BrokerConfigJobs {

    /**
     * Configuration drift (ADR-0067 D8): every declared cluster's live nodes are read
     * once — one batched pass per node under the per-node limiter — and compared
     * against the declaration. It records findings and never applies.
     */
    @Bean
    ScheduledJob configDriftJob(BrokerConfigDriftService drift, SettingsService settings) {
        return ScheduledJob.fixedDelay(
                "config-drift",
                "brokerconfig",
                () -> settings.duration(BrokerConfigSettings.DRIFT_INTERVAL),
                drift::evaluateAll);
    }
}
