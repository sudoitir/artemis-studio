package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class LoginAttemptLimiterTest extends PostgresIntegrationTest {

    /** Every test has its own usernames and addresses, so tests that share the database do not meet. */
    private final String tag = UUID.randomUUID().toString();

    private final MutableClock clock = new MutableClock();

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private LoginAttemptLimiter limiter;

    @BeforeEach
    void limiter() {
        limiter = new LoginAttemptLimiter(jdbc, clock, transactions);
    }

    private String name(String name) {
        return name + "-" + tag;
    }

    private String ip(String address) {
        return address + "-" + tag;
    }

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-13T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private void pass(Duration time) {
        clock.now = clock.now.plus(time);
    }

    private void fail(String username, String source, int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(username, source);
        }
    }

    @Test
    void locksAKeyAfterFiveFailuresThenLiftsIt() {
        fail(name("alice"), ip("10.0.0.1"), 4);
        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.1"))).isFalse();

        fail(name("alice"), ip("10.0.0.1"), 1);
        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.1"))).isTrue();
        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.2"))).isFalse();
        assertThat(limiter.isLocked(name("bob"), ip("10.0.0.1"))).isFalse();

        pass(Duration.ofSeconds(6));
        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.1"))).isFalse();
    }

    @Test
    void theBackoffDoublesWithEachFurtherFailureUpToFiveMinutes() {
        fail(name("alice"), ip("10.0.0.1"), 6);
        pass(Duration.ofSeconds(6));
        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.1"))).isTrue();
        pass(Duration.ofSeconds(5));
        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.1"))).isFalse();

        fail(name("alice"), ip("10.0.0.1"), 20);
        pass(Duration.ofSeconds(299));
        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.1"))).isTrue();
        pass(Duration.ofSeconds(2));
        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.1"))).isFalse();
    }

    @Test
    void aSuccessClearsTheAccountKey() {
        fail(name("alice"), ip("10.0.0.1"), 5);

        limiter.recordSuccess(name("alice"), ip("10.0.0.1"));

        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.1"))).isFalse();
        fail(name("alice"), ip("10.0.0.1"), 4);
        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.1"))).isFalse();
    }

    @Test
    void anAccountKeyIsForgottenAfterItsLastFailure() {
        fail(name("alice"), ip("10.0.0.1"), 4);
        pass(Duration.ofMinutes(14));
        fail(name("alice"), ip("10.0.0.1"), 1);
        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.1"))).isTrue();

        pass(Duration.ofMinutes(16));
        fail(name("alice"), ip("10.0.0.1"), 4);
        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.1"))).isFalse();
    }

    @Test
    void oneAddressSprayingManyUnknownUsernamesIsThrottledAtThirty() {
        for (int i = 0; i < 29; i++) {
            fail(name("ghost-" + i), ip("10.0.0.9"), 1);
        }
        assertThat(limiter.isLocked(name("ghost-new"), ip("10.0.0.9"))).isFalse();

        fail(name("ghost-29"), ip("10.0.0.9"), 1);

        assertThat(limiter.isLocked(name("ghost-new"), ip("10.0.0.9"))).isTrue();
        assertThat(limiter.isLocked(name("a-real-user"), ip("10.0.0.9"))).isTrue();
        assertThat(limiter.isLocked(name("ghost-new"), ip("10.0.0.10"))).isFalse();
    }

    @Test
    void theAddressWindowIsFixedFromTheFirstFailureAndASuccessDoesNotClearIt() {
        fail(name("ghost-0"), ip("10.0.0.9"), 29);
        pass(Duration.ofMinutes(9));
        limiter.recordSuccess(name("alice"), ip("10.0.0.9"));
        fail(name("ghost-1"), ip("10.0.0.9"), 1);
        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.9"))).isTrue();

        pass(Duration.ofMinutes(2));
        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.9"))).isFalse();
    }

    @Test
    void forgettingAUsernameClearsItForEverySourceButNotTheAddressCount() {
        fail(name("alice"), ip("10.0.0.1"), 5);
        fail(name("alice"), ip("10.0.0.2"), 5);
        fail(name("bob"), ip("10.0.0.1"), 5);

        limiter.forget(name("alice"));

        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.1"))).isFalse();
        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.2"))).isFalse();
        assertThat(limiter.isLocked(name("bob"), ip("10.0.0.1"))).isTrue();
    }

    @Test
    void anUnknownSourceIsKeyedTogether() {
        fail(name("alice"), null, 5);

        assertThat(limiter.isLocked(name("alice"), null)).isTrue();
    }

    @Test
    void failuresCountedByTwoReplicasLockTheSameAccount() {
        var other = new LoginAttemptLimiter(jdbc, clock, transactions);

        for (int i = 0; i < 5; i++) {
            (i % 2 == 0 ? limiter : other).recordFailure(name("alice"), ip("10.0.0.1"));
        }

        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.1"))).isTrue();
        assertThat(other.isLocked(name("alice"), ip("10.0.0.1"))).isTrue();
    }

    @Test
    void aFailureStaysCountedWhenTheSigninTransactionRollsBack() {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            fail(name("alice"), ip("10.0.0.1"), 5);
            tx.setRollbackOnly();
        });

        assertThat(limiter.isLocked(name("alice"), ip("10.0.0.1"))).isTrue();
    }

    @Test
    void aUsernameDoesNotRunIntoItsSource() {
        // "a" at "b:c" and "a:1:b" at "c" would be one key without the length in front.
        fail(name("a"), "b:c", 5);

        assertThat(limiter.isLocked(name("a"), "b:c")).isTrue();
        assertThat(limiter.isLocked(name("a") + ":b", "c")).isFalse();
    }
}
