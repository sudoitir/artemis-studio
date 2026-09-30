package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class LifecycleJobs {

    @Bean
    ScheduledJob housekeepingJob(Housekeeper housekeeper, SettingsService settings) {
        return ScheduledJob.cron(
                "housekeeping",
                LifecycleModule.ID,
                ScheduledJob.Scope.INSTALLATION,
                () -> settings.value(LifecycleSettings.HOUSEKEEPING_CRON),
                housekeeper::purgeAll);
    }

    @Bean
    ScheduledJob storageSampleJob(StorageHealthService health, SettingsService settings) {
        return ScheduledJob.cron(
                "storage-sample",
                LifecycleModule.ID,
                ScheduledJob.Scope.INSTALLATION,
                () -> settings.value(LifecycleSettings.STORAGE_SAMPLE_CRON),
                health::sample);
    }
}
