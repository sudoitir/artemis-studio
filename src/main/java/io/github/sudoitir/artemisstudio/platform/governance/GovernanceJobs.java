package io.github.sudoitir.artemisstudio.platform.governance;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The content policy's background work (ADR-0075 D4). */
@Configuration(proxyBeanMethods = false)
class GovernanceJobs {

    @Bean
    ScheduledJob governanceFindingsFlushJob(FindingsRecorder findings) {
        return ScheduledJob.fixedDelay(
                "governance-findings-flush",
                "governance",
                ScheduledJob.Scope.INSTANCE,
                () -> Duration.ofSeconds(30),
                findings::flush);
    }

    @Bean
    ScheduledJob governanceRemaskJob(GovernanceRemasking remasking) {
        return ScheduledJob.fixedDelay(
                "governance-remask",
                "governance",
                ScheduledJob.Scope.INSTANCE,
                () -> Duration.ofMinutes(1),
                remasking::run);
    }

    @Bean
    ScheduledJob governancePolicyRefreshJob(PolicyStore store) {
        return ScheduledJob.fixedDelay(
                "governance-policy-refresh",
                "governance",
                ScheduledJob.Scope.INSTANCE,
                () -> Duration.ofSeconds(30),
                store::refreshIfStale);
    }
}
