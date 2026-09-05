package io.github.sudoitir.artemisstudio.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties.RateLimit;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class NodeCallLimiterTest {

    private static NodeCallLimiter limiter(int perSecond) {
        return new NodeCallLimiter(new ArtemisStudioProperties(
                null, null, null, new RateLimit(perSecond), null, null, null, null, null, null, null));
    }

    @Test
    void allowsUpToTheCeilingThenBlocksUntilTheNextRefill() throws Exception {
        NodeCallLimiter limiter = limiter(2);
        UUID node = UUID.randomUUID();

        limiter.acquire(node);
        limiter.acquire(node); // ceiling = 2, both immediate

        AtomicBoolean third = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try {
                limiter.acquire(node);
                third.set(true);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();
        Thread.sleep(150);
        assertThat(third).as("blocked at the per-node ceiling").isFalse();

        limiter.refill();
        t.join(2_000);
        assertThat(third).as("released by the refill").isTrue();
    }

    @Test
    void oneExhaustedNodeDoesNotStallAnother() throws Exception {
        NodeCallLimiter limiter = limiter(1);
        UUID busy = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        limiter.acquire(busy); // busy bucket now empty

        long start = System.nanoTime();
        limiter.acquire(other); // independent bucket — must not wait on `busy`
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void refillToppingUpNeverExceedsTheCeiling() throws Exception {
        NodeCallLimiter limiter = limiter(3);
        UUID node = UUID.randomUUID();
        limiter.acquire(node); // materialise the bucket

        // Many refills in a row must not accumulate permits beyond the ceiling.
        for (int i = 0; i < 10; i++) {
            limiter.refill();
        }

        limiter.acquire(node);
        limiter.acquire(node);
        limiter.acquire(node); // exactly the ceiling is available again

        AtomicBoolean overCeiling = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try {
                limiter.acquire(node);
                overCeiling.set(true);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();
        Thread.sleep(150);
        assertThat(overCeiling).as("no 4th permit despite 10 refills").isFalse();
        t.interrupt();
        t.join(2_000);
    }
}
