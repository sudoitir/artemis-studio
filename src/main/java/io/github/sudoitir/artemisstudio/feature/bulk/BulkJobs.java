package io.github.sudoitir.artemisstudio.feature.bulk;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class BulkJobs {

    /** Finds the runs of a replica that is gone (ADR-0148): the state is in the database, so one replica does it. */
    @Bean
    ScheduledJob bulkRecoveryJob(BulkRecovery recovery) {
        return ScheduledJob.fixedDelay(
                "bulk-recovery",
                "bulk",
                ScheduledJob.Scope.INSTALLATION,
                () -> Duration.ofSeconds(30),
                recovery::recover);
    }
}
