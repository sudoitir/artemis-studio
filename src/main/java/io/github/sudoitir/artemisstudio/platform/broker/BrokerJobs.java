package io.github.sudoitir.artemisstudio.platform.broker;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The per-node call budget and clock discipline (ADR-0053). Not settings-tunable: the
 * refill tick is the unit the per-second budget is measured in, and the clock jobs are
 * properties of how time is measured rather than of how hard Studio polls a broker.
 * None makes a broker call beyond the offset reads already batched.
 */
@Configuration(proxyBeanMethods = false)
class BrokerJobs {

    private static final String FEATURE = "broker";

    @Bean
    ScheduledJob nodeCallRefillJob(NodeCallLimiter limiter) {
        return ScheduledJob.fixedDelay(
                "node-call-refill", FEATURE, ScheduledJob.Scope.INSTANCE, () -> Duration.ofSeconds(1), limiter::refill);
    }

    @Bean
    ScheduledJob clockOffsetRefreshJob(ClockOffsetService offsets) {
        return ScheduledJob.fixedDelay(
                "clock-offset-refresh",
                FEATURE,
                ScheduledJob.Scope.INSTANCE,
                () -> ClockOffsetService.REFRESH_INTERVAL,
                offsets::refresh);
    }

    @Bean
    ScheduledJob monotonicClockWatchJob(MonotonicClockWatch watch) {
        return ScheduledJob.fixedDelay(
                "monotonic-clock-watch",
                FEATURE,
                ScheduledJob.Scope.INSTANCE,
                () -> MonotonicClockWatch.INTERVAL,
                watch::check);
    }
}
