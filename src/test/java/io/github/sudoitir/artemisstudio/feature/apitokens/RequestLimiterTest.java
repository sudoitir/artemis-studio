package io.github.sudoitir.artemisstudio.feature.apitokens;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The request window lives in the database, so two limiters on one database, as two replicas are,
 * share one count. The limiter holds no window of its own.
 */
class RequestLimiterTest extends PostgresIntegrationTest {

    private static final long MINUTE = 60_000;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void twoReplicasShareOneMinuteAndTheLimitIsExact() {
        RequestLimiter a = new RequestLimiter(jdbc);
        RequestLimiter b = new RequestLimiter(jdbc);
        UUID key = UUID.randomUUID();
        long now = 10 * MINUTE + 1_000;

        RequestLimiter.Decision first = a.acquire(key, 3, now);
        RequestLimiter.Decision second = b.acquire(key, 3, now);
        RequestLimiter.Decision third = a.acquire(key, 3, now);
        RequestLimiter.Decision fourth = b.acquire(key, 3, now);

        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(2);
        assertThat(second.remaining()).isEqualTo(1);
        assertThat(third.allowed()).isTrue();
        assertThat(third.remaining()).isZero();
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.remaining()).isZero();
        assertThat(fourth.limit()).isEqualTo(3);
        assertThat(fourth.resetSeconds()).isEqualTo(59);
        // A refused request is not counted, so one refund leaves room for exactly one more.
        b.refund(key, now);
        assertThat(a.acquire(key, 3, now).allowed()).isTrue();
        assertThat(a.acquire(key, 3, now).allowed()).isFalse();
    }

    @Test
    void aNewMinuteStartsFromZeroAndDropsTheOldOne() {
        RequestLimiter limiter = new RequestLimiter(jdbc);
        UUID key = UUID.randomUUID();
        limiter.acquire(key, 1, 20 * MINUTE);
        assertThat(limiter.acquire(key, 1, 20 * MINUTE).allowed()).isFalse();

        RequestLimiter.Decision next = limiter.acquire(key, 1, 21 * MINUTE);

        assertThat(next.allowed()).isTrue();
        assertThat(jdbc.queryForList("SELECT minute FROM api_request_window WHERE key = ?", Long.class, key))
                .containsExactly(21L);
    }

    @Test
    void theKeysAreCountedApart() {
        RequestLimiter limiter = new RequestLimiter(jdbc);

        assertThat(limiter.acquire(UUID.randomUUID(), 1, 30 * MINUTE).allowed()).isTrue();
        assertThat(limiter.acquire(UUID.randomUUID(), 1, 30 * MINUTE).allowed()).isTrue();
    }

    @Test
    void theInFlightLimitIsPerLimiter() {
        RequestLimiter limiter = new RequestLimiter(jdbc);
        UUID key = UUID.randomUUID();

        assertThat(limiter.enter(key, 1)).isTrue();
        assertThat(limiter.enter(key, 1)).isFalse();
        limiter.exit(key);
        assertThat(limiter.enter(key, 1)).isTrue();
    }
}
