package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.kernel.core.ShutdownPhases;
import io.github.sudoitir.rahdar.kernel.core.ShutdownStep;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Every resource Rahdar holds on a broker is released at its phase (operational-health spec). Scraping is a set of jobs,
 * so it stops with the "jobs" step.
 */
class ShutdownOrderTest extends PostgresIntegrationTest {

    @Autowired
    List<ShutdownStep> steps;

    @Test
    void eachResourceIsReleasedAtItsPhase() {
        Map<String, Integer> phases =
                steps.stream().collect(Collectors.toMap(ShutdownStep::name, ShutdownStep::getPhase));

        assertThat(phases)
                .containsEntry("stream", ShutdownPhases.STREAM)
                .containsEntry("runs", ShutdownPhases.RUNS)
                .containsEntry("jobs", ShutdownPhases.JOBS)
                .containsEntry("broker-events", ShutdownPhases.BUFFERS)
                .containsEntry("broker-calls", ShutdownPhases.BROKER_CALLS)
                .containsEntry("sql-tail-polls", ShutdownPhases.BROKER_CALLS)
                .containsEntry("subscriptions", ShutdownPhases.SUBSCRIPTIONS)
                .containsEntry("capture", ShutdownPhases.SUBSCRIPTIONS)
                .containsEntry("core-pool", ShutdownPhases.CORE_POOL);
    }
}
