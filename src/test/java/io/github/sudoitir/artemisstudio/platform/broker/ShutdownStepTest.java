package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import io.github.sudoitir.artemisstudio.kernel.core.ShutdownStep;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The shutdown sequence (operational-health spec) and the gate that ends broker calls. */
class ShutdownStepTest {

    private static final String NODE = "http://a:8161/console/jolokia";

    private static NodeCallLimiter limiter() {
        return new NodeCallLimiter(
                new RateLimitProperties(10), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    void phasesStopStreamFirstAndTheCorePoolLast() {
        // Spring stops the highest phase first.
        assertThat(ShutdownPhases.STREAM).isGreaterThan(ShutdownPhases.JOBS);
        assertThat(ShutdownPhases.JOBS).isGreaterThan(ShutdownPhases.BUFFERS);
        assertThat(ShutdownPhases.BUFFERS).isGreaterThan(ShutdownPhases.BROKER_CALLS);
        assertThat(ShutdownPhases.BROKER_CALLS).isGreaterThan(ShutdownPhases.SUBSCRIPTIONS);
        assertThat(ShutdownPhases.SUBSCRIPTIONS).isGreaterThan(ShutdownPhases.CORE_POOL);
    }

    @Test
    void aStepReleasesOnceAndOnlyAfterItStarted() {
        AtomicInteger released = new AtomicInteger();
        ShutdownStep step = new ShutdownStep("demo", ShutdownPhases.STREAM, released::incrementAndGet);

        step.stop();
        assertThat(released).hasValue(0);

        step.start();
        step.stop();
        step.stop();
        assertThat(released).hasValue(1);
        assertThat(step.isRunning()).isFalse();
    }

    @Test
    void aStoppedContextThatStartsAgainReopensTheGate() {
        NodeCallLimiter limiter = limiter();
        ShutdownStep gate =
                new ShutdownStep("broker-calls", ShutdownPhases.BROKER_CALLS, limiter::close, limiter::open);
        gate.start();

        gate.stop();
        assertThatThrownBy(() -> limiter.acquire(NODE, 1)).isInstanceOf(BrokerConnectionException.class);

        gate.start();
        limiter.acquire(NODE, 1);
    }

    @Test
    void noBrokerCallStartsOnceTheGateHasClosed() {
        NodeCallLimiter limiter = limiter();
        limiter.acquire(NODE, 1);

        limiter.close();

        assertThatThrownBy(() -> limiter.acquire(NODE, 1))
                .isInstanceOf(BrokerConnectionException.class)
                .hasMessageContaining("shutting down");
    }
}
