package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.partition.PartitionMaintainer;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.partition.PartitionSpec;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.time.Duration;
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

    /** Partitions ahead, closed ones frozen, parents analyzed; the same work runs once at start-up (ADR-0193). */
    @Bean
    ScheduledJob partitionsJob(PartitionMaintainer maintainer) {
        return ScheduledJob.fixedDelay(
                "partitions",
                LifecycleModule.ID,
                ScheduledJob.Scope.INSTALLATION,
                () -> Duration.ofHours(1),
                maintainer::maintain);
    }

    /** The audit trail is partitioned by month (ADR-0190, ADR-0193). */
    @Bean
    PartitionSpec auditEventPartitions() {
        return AuditStore.PARTITIONS;
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
