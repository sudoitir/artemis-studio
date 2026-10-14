package io.github.sudoitir.rahdar.feature.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** The upload window is in the database, so two limiters on one database, as two replicas are, share it. */
class UploadRateLimitTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void theSixthUploadInAnHourIsRefusedWhicheverReplicaTakesIt() {
        Clock now = Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneOffset.UTC);
        var a = new UploadRateLimit(jdbc, now, transactions);
        var b = new UploadRateLimit(jdbc, now, transactions);
        UUID user = UUID.randomUUID();

        for (int i = 0; i < UploadRateLimit.LIMIT; i++) {
            assertThat((i % 2 == 0 ? a : b).tryAcquire(user)).isTrue();
        }
        assertThat(a.tryAcquire(user)).isFalse();
        assertThat(b.tryAcquire(user)).isFalse();
        assertThat(a.tryAcquire(UUID.randomUUID())).as("per user").isTrue();
    }

    @Test
    void theWindowEndsAnHourAfterTheFirstUpload() {
        UUID user = UUID.randomUUID();
        var first = new UploadRateLimit(
                jdbc, Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneOffset.UTC), transactions);
        for (int i = 0; i < UploadRateLimit.LIMIT; i++) {
            first.tryAcquire(user);
        }
        var later = new UploadRateLimit(
                jdbc, Clock.fixed(Instant.parse("2026-10-13T11:00:01Z"), ZoneOffset.UTC), transactions);

        assertThat(later.tryAcquire(user)).isTrue();
    }
}
