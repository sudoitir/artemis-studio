package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import io.github.sudoitir.artemisstudio.kernel.core.ShutdownStep;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The shutdown sequence (operational-health spec) and the gate that ends broker calls. */
class ShutdownStepTest {

    @Test
    void phasesStopStreamFirstAndTheCorePoolLast() {
        // Spring stops the highest phase first.
        assertThat(ShutdownPhases.STREAM).isGreaterThan(ShutdownPhases.BROKER_CALLS);
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
    void aStoppedContextThatStartsAgainReopensTheGate() throws InterruptedException {
        NodeCallLimiter limiter = new NodeCallLimiter(new RateLimitProperties(10));
        ShutdownStep gate =
                new ShutdownStep("broker-calls", ShutdownPhases.BROKER_CALLS, limiter::close, limiter::open);
        UUID node = UUID.randomUUID();
        gate.start();

        gate.stop();
        assertThatThrownBy(() -> limiter.acquire(node)).isInstanceOf(BrokerConnectionException.class);

        gate.start();
        limiter.acquire(node);
    }

    @Test
    void noBrokerCallStartsOnceTheGateHasClosed() throws InterruptedException {
        NodeCallLimiter limiter = new NodeCallLimiter(new RateLimitProperties(10));
        UUID node = UUID.randomUUID();
        limiter.acquire(node);

        limiter.close();

        assertThatThrownBy(() -> limiter.acquire(node))
                .isInstanceOf(BrokerConnectionException.class)
                .hasMessageContaining("shutting down");
    }
}
