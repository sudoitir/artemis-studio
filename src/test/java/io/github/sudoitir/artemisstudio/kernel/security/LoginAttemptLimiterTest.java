package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

    private final AtomicLong now = new AtomicLong();
    private final Ticker ticker = now::get;
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(ticker);

    private void pass(Duration time) {
        now.addAndGet(time.toNanos());
    }

    private void fail(String username, String source, int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(username, source);
        }
    }

    @Test
    void locksAKeyAfterFiveFailuresThenLiftsIt() {
        fail("alice", "10.0.0.1", 4);
        assertThat(limiter.isLocked("alice", "10.0.0.1")).isFalse();

        fail("alice", "10.0.0.1", 1);
        assertThat(limiter.isLocked("alice", "10.0.0.1")).isTrue();
        assertThat(limiter.isLocked("alice", "10.0.0.2")).isFalse();
        assertThat(limiter.isLocked("bob", "10.0.0.1")).isFalse();

        pass(Duration.ofSeconds(6));
        assertThat(limiter.isLocked("alice", "10.0.0.1")).isFalse();
    }

    @Test
    void theBackoffDoublesWithEachFurtherFailureUpToFiveMinutes() {
        fail("alice", "10.0.0.1", 6);
        pass(Duration.ofSeconds(6));
        assertThat(limiter.isLocked("alice", "10.0.0.1")).isTrue();
        pass(Duration.ofSeconds(5));
        assertThat(limiter.isLocked("alice", "10.0.0.1")).isFalse();

        fail("alice", "10.0.0.1", 20);
        pass(Duration.ofSeconds(299));
        assertThat(limiter.isLocked("alice", "10.0.0.1")).isTrue();
        pass(Duration.ofSeconds(2));
        assertThat(limiter.isLocked("alice", "10.0.0.1")).isFalse();
    }

    @Test
    void aSuccessClearsTheAccountKey() {
        fail("alice", "10.0.0.1", 5);

        limiter.recordSuccess("alice", "10.0.0.1");

        assertThat(limiter.isLocked("alice", "10.0.0.1")).isFalse();
        fail("alice", "10.0.0.1", 4);
        assertThat(limiter.isLocked("alice", "10.0.0.1")).isFalse();
    }

    @Test
    void anAccountKeyIsForgottenAfterItsLastFailure() {
        fail("alice", "10.0.0.1", 4);
        pass(Duration.ofMinutes(14));
        fail("alice", "10.0.0.1", 1);
        assertThat(limiter.isLocked("alice", "10.0.0.1")).isTrue();

        pass(Duration.ofMinutes(16));
        fail("alice", "10.0.0.1", 4);
        assertThat(limiter.isLocked("alice", "10.0.0.1")).isFalse();
    }

    @Test
    void oneAddressSprayingManyUnknownUsernamesIsThrottledAtThirty() {
        for (int i = 0; i < 29; i++) {
            fail("ghost-" + i, "10.0.0.9", 1);
        }
        assertThat(limiter.isLocked("ghost-new", "10.0.0.9")).isFalse();

        fail("ghost-29", "10.0.0.9", 1);

        assertThat(limiter.isLocked("ghost-new", "10.0.0.9")).isTrue();
        assertThat(limiter.isLocked("a-real-user", "10.0.0.9")).isTrue();
        assertThat(limiter.isLocked("ghost-new", "10.0.0.10")).isFalse();
    }

    @Test
    void theAddressWindowIsFixedFromTheFirstFailureAndASuccessDoesNotClearIt() {
        fail("ghost-0", "10.0.0.9", 29);
        pass(Duration.ofMinutes(9));
        limiter.recordSuccess("alice", "10.0.0.9");
        fail("ghost-1", "10.0.0.9", 1);
        assertThat(limiter.isLocked("alice", "10.0.0.9")).isTrue();

        pass(Duration.ofMinutes(2));
        assertThat(limiter.isLocked("alice", "10.0.0.9")).isFalse();
    }

    @Test
    void anUnknownSourceIsKeyedTogether() {
        fail("alice", null, 5);

        assertThat(limiter.isLocked("alice", null)).isTrue();
    }
}
