package io.github.sudoitir.artemisstudio.platform.broker;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Clock discipline (ADR-0053). Not settings-tunable: both are properties of how time
 * is measured rather than of how hard Studio polls a broker, and neither makes a
 * broker call beyond the offset reads it already batches.
 */
@Configuration(proxyBeanMethods = false)
class BrokerJobs {

    @Bean
    ScheduledJob clockOffsetRefreshJob(ClockOffsetService offsets) {
        return ScheduledJob.fixedDelay(
                "clock-offset-refresh", "broker", () -> ClockOffsetService.REFRESH_INTERVAL, offsets::refresh);
    }

    @Bean
    ScheduledJob monotonicClockWatchJob(MonotonicClockWatch watch) {
        return ScheduledJob.fixedDelay(
                "monotonic-clock-watch", "broker", () -> MonotonicClockWatch.INTERVAL, watch::check);
    }
}
