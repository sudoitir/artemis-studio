package io.github.sudoitir.artemisstudio.kernel.jobs.internal;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import io.github.sudoitir.artemisstudio.kernel.core.ShutdownStep;
import io.github.sudoitir.artemisstudio.kernel.jobs.JobStatuses;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Background jobs stop before anything they use is released (operational-health spec). */
@Configuration(proxyBeanMethods = false)
class JobsShutdownSteps {

    /** Long enough for a normal pass to finish; a stuck one is not allowed to hold shutdown. */
    private static final Duration IN_FLIGHT_WAIT = Duration.ofSeconds(10);

    @Bean
    ShutdownStep jobsShutdown(JobStatuses jobs) {
        return new ShutdownStep("jobs", ShutdownPhases.JOBS, () -> jobs.pause(IN_FLIGHT_WAIT), jobs::resume);
    }
}
