package io.github.sudoitir.artemisstudio.kernel.jobs.internal;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import io.github.sudoitir.artemisstudio.kernel.core.ShutdownStep;
import io.github.sudoitir.artemisstudio.kernel.jobs.BackgroundRuns;
import io.github.sudoitir.artemisstudio.kernel.jobs.JobStatuses;
import io.github.sudoitir.artemisstudio.kernel.replica.HaProperties;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runs and background jobs stop before anything they use is released (operational-health spec). */
@Configuration(proxyBeanMethods = false)
class JobsShutdownSteps {

    /** Long enough for a normal pass to finish; a stuck one is not allowed to hold shutdown. */
    private static final Duration IN_FLIGHT_WAIT = Duration.ofSeconds(10);

    /** Bulk runs and transfers get the grace to finish, then are stopped and recorded interrupted (ADR-0148). */
    @Bean
    ShutdownStep runsShutdown(BackgroundRuns runs, HaProperties ha) {
        return new ShutdownStep("runs", ShutdownPhases.RUNS, () -> runs.stopForShutdown(ha.runGrace()));
    }

    @Bean
    ShutdownStep jobsShutdown(JobStatuses jobs) {
        return new ShutdownStep("jobs", ShutdownPhases.JOBS, () -> jobs.pause(IN_FLIGHT_WAIT), jobs::resume);
    }
}
