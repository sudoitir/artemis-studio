package io.github.sudoitir.artemisstudio.feature.setupreview;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SetupReviewJobs {

    /** Every cluster, one batched read per node, under the cluster's setup-review lock (ADR-0106). */
    @Bean
    ScheduledJob setupReviewJob(SetupReviewService reviews, SettingsService settings) {
        return ScheduledJob.fixedDelay(
                "setup-review",
                "setupreview",
                () -> settings.duration(SetupReviewSettings.INTERVAL),
                reviews::reviewAll);
    }
}
