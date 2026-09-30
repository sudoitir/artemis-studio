package io.github.sudoitir.artemisstudio.platform.governance;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The content policy's background work (ADR-0075 D4). */
@Configuration(proxyBeanMethods = false)
class GovernanceJobs {

    private static final String FEATURE = "governance";

    @Bean
    ScheduledJob governanceFindingsFlushJob(FindingsRecorder findings) {
        return ScheduledJob.fixedDelay(
                "governance-findings-flush",
                FEATURE,
                ScheduledJob.Scope.INSTANCE,
                () -> Duration.ofSeconds(30),
                findings::flush);
    }

    @Bean
    ScheduledJob governanceRemaskJob(GovernanceRemasking remasking) {
        return ScheduledJob.fixedDelay(
                "governance-remask", FEATURE, ScheduledJob.Scope.INSTANCE, () -> Duration.ofMinutes(1), remasking::run);
    }

    @Bean
    ScheduledJob governancePolicyRefreshJob(PolicyStore store) {
        return ScheduledJob.fixedDelay(
                "governance-policy-refresh",
                FEATURE,
                ScheduledJob.Scope.INSTANCE,
                () -> Duration.ofSeconds(30),
                store::refreshIfStale);
    }
}
