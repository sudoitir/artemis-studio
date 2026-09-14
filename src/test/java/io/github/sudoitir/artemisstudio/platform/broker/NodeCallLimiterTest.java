package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class NodeCallLimiterTest {

    private static final String NODE = "http://a:8161/console/jolokia";
    private static final String OTHER = "http://b:8161/console/jolokia";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private NodeCallLimiter limiter(int perSecond) {
        return new NodeCallLimiter(new RateLimitProperties(perSecond), meters);
    }

    @Test
    void allowsUpToTheCeilingThenBlocksUntilTheNextRefill() throws Exception {
        NodeCallLimiter limiter = limiter(2);

        limiter.acquire(NODE, 1);
        limiter.acquire(NODE, 1); // ceiling = 2, both immediate

        AtomicBoolean third = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            limiter.acquire(NODE, 1);
            third.set(true);
        });
        t.start();
        Thread.sleep(150);
        assertThat(third).as("blocked at the per-node ceiling").isFalse();

        limiter.refill();
        t.join(2_000);
        assertThat(third).as("released by the refill").isTrue();
    }

    @Test
    void oneExhaustedNodeDoesNotStallAnother() {
        NodeCallLimiter limiter = limiter(1);

        limiter.acquire(NODE, 1); // this bucket is now empty

        long start = System.nanoTime();
        limiter.acquire(OTHER, 1); // independent bucket — must not wait on NODE
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void refillToppingUpNeverExceedsTheCeiling() throws Exception {
        NodeCallLimiter limiter = limiter(3);
        limiter.acquire(NODE, 1); // materialise the bucket

        // Many refills in a row must not accumulate permits beyond the ceiling.
        for (int i = 0; i < 10; i++) {
            limiter.refill();
        }

        limiter.acquire(NODE, 3); // exactly the ceiling is available again

        AtomicBoolean overCeiling = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try {
                limiter.acquire(NODE, 1);
                overCeiling.set(true);
            } catch (BrokerConnectionException interrupted) {
                // expected once interrupted below
            }
        });
        t.start();
        Thread.sleep(150);
        assertThat(overCeiling).as("no 4th permit despite 10 refills").isFalse();
        t.interrupt();
        t.join(2_000);
    }

    @Test
    void aRequestNeedingMoreThanTheCeilingSpreadsOverRefillsInsteadOfDeadlocking() throws Exception {
        NodeCallLimiter limiter = limiter(2);
        AtomicBoolean done = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            limiter.acquire(NODE, 5);
            done.set(true);
        });
        t.start();
        for (int i = 0; i < 3 && !done.get(); i++) {
            Thread.sleep(100);
            limiter.refill();
        }
        t.join(2_000);

        assertThat(done)
                .as("five permits taken across refills at a ceiling of two")
                .isTrue();
    }

    @Test
    void anInterruptedWaitIsAConnectionErrorAndKeepsTheInterrupt() throws Exception {
        NodeCallLimiter limiter = limiter(1);
        limiter.acquire(NODE, 1);
        AtomicBoolean interruptKept = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            assertThatThrownBy(() -> limiter.acquire(NODE, 1)).isInstanceOf(BrokerConnectionException.class);
            interruptKept.set(Thread.currentThread().isInterrupted());
        });
        t.start();
        Thread.sleep(100);
        t.interrupt();
        t.join(2_000);

        assertThat(interruptKept).isTrue();
    }

    @Test
    void everyRequestIsCountedPerNode() {
        NodeCallLimiter limiter = limiter(10);

        limiter.acquire(NODE, 1);
        limiter.acquire(NODE, 2);
        limiter.acquire(OTHER, 1);

        assertThat(meters.counter("studio.broker.requests", "node", NODE).count())
                .isEqualTo(2.0);
        assertThat(meters.counter("studio.broker.requests", "node", OTHER).count())
                .isEqualTo(1.0);
    }

    @Test
    void aClosedLimiterRefusesNewCalls() {
        NodeCallLimiter limiter = limiter(10);
        limiter.close();

        assertThatThrownBy(() -> limiter.acquire(NODE, 1))
                .isInstanceOf(BrokerConnectionException.class)
                .hasMessageContaining("shutting down");
    }
}
