package io.github.sudoitir.artemisstudio.feature.transfer;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TransferJobs {

    /** Finds the runs of a replica that is gone (ADR-0152): the state is in the database, so one replica does it. */
    @Bean
    ScheduledJob transferRecoveryJob(TransferRecovery recovery) {
        return ScheduledJob.fixedDelay(
                "transfer-recovery",
                "transfer",
                ScheduledJob.Scope.INSTALLATION,
                () -> Duration.ofSeconds(30),
                recovery::recover);
    }
}
