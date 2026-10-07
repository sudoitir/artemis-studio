package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The approval gate's background work (ADR-0180). */
@Configuration(proxyBeanMethods = false)
class ApprovalJobs {

    static final Duration INTERVAL = Duration.ofSeconds(30);

    /** An approved request the approving replica did not start within this is the runner's to start. */
    static final Duration RUNNER_GRACE = Duration.ofSeconds(30);

    /** Starts approved requests a busy or stopped replica left behind. Claims are exclusive, so any replica may. */
    @Bean
    ScheduledJob approvalRunnerJob(Executions executions) {
        return ScheduledJob.fixedDelay(
                "approval-runner",
                ApprovalModule.ID,
                ScheduledJob.Scope.INSTALLATION,
                () -> INTERVAL,
                () -> executions.runDue(RUNNER_GRACE));
    }

    /** Expires, recovers and sweeps held requests. */
    @Bean
    ScheduledJob approvalExpiryJob(HeldMaintenance maintenance) {
        return ScheduledJob.fixedDelay(
                "approval-expiry",
                ApprovalModule.ID,
                ScheduledJob.Scope.INSTALLATION,
                () -> INTERVAL,
                maintenance::sweep);
    }

    /** While break-glass is on, the hourly WARN on every instance. */
    @Bean
    ScheduledJob breakGlassReminderJob(BreakGlass breakGlass) {
        return ScheduledJob.fixedDelay(
                "break-glass-reminder",
                ApprovalModule.ID,
                ScheduledJob.Scope.INSTANCE,
                () -> Duration.ofHours(1),
                breakGlass::remind);
    }
}
