package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AlertingJobs {

    @Bean
    ScheduledJob alertDispatchJob(AlertDispatcher dispatcher, SettingsService settings) {
        return ScheduledJob.fixedDelay(
                "alert-dispatch",
                "alerting",
                () -> settings.duration(AlertingSettings.DISPATCH_INTERVAL),
                dispatcher::dispatch);
    }
}
