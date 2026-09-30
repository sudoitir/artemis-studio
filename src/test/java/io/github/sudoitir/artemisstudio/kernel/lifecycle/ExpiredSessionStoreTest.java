package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link ExpiredSessionStore} against a real Postgres: sessions expired before the cutoff go, with their attributes. */
class ExpiredSessionStoreTest extends PostgresIntegrationTest {

    private static final String MARK = "lifecycle-test";

    @Autowired
    ExpiredSessionStore store;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Environment env;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM spring_session WHERE principal_name = ?", MARK);
    }

    /** A session that expired {@code expiredMinutesAgo} minutes ago (negative: still valid), with one attribute. */
    private void session(int expiredMinutesAgo) {
        String id = UUID.randomUUID().toString();
        long now = Instant.now().toEpochMilli();
        jdbc.update(
                "INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time,"
                        + " max_inactive_interval, expiry_time, principal_name) VALUES (?, ?, ?, ?, 60, ?, ?)",
                id,
                UUID.randomUUID().toString(),
                now,
                now,
                now - expiredMinutesAgo * 60_000L,
                MARK);
        jdbc.update(
                "INSERT INTO spring_session_attributes (session_primary_id, attribute_name, attribute_bytes)"
                        + " VALUES (?, 'a', ?)",
                id,
                new byte[] {1});
    }

    private int sessions() {
        return jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class, MARK);
    }

    private int attributes() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM spring_session_attributes a JOIN spring_session s"
                        + " ON s.primary_id = a.session_primary_id WHERE s.principal_name = ?",
                Integer.class,
                MARK);
    }

    @Test
    void springSessionsOwnCleanupIsOff() {
        assertThat(env.getProperty("spring.session.jdbc.cleanup-cron")).isEqualTo("-");
    }

    @Test
    void previewCountsExpiredSessionsAndDeletesNothing() {
        session(10);
        session(5);
        session(-30);
        Instant cutoff = Instant.now().minus(1, ChronoUnit.MINUTES);

        assertThat(store.preview(cutoff).rows()).isGreaterThanOrEqualTo(2);
        assertThat(sessions()).isEqualTo(3);
    }

    @Test
    void purgeBatchRemovesOnlySessionsExpiredBeforeTheCutoffAndEndsAtZero() {
        for (int i = 0; i < 4; i++) {
            session(10 + i);
        }
        session(-30);
        Instant cutoff = Instant.now().minus(1, ChronoUnit.MINUTES);

        long total = 0;
        long batch;
        do {
            batch = store.purgeBatch(cutoff, 2);
            assertThat(batch).isLessThanOrEqualTo(2);
            total += batch;
        } while (batch > 0);

        assertThat(total).isGreaterThanOrEqualTo(4);
        assertThat(sessions()).isEqualTo(1);
        assertThat(attributes()).isEqualTo(1);
        assertThat(store.purgeBatch(cutoff, 2)).isZero();
    }
}
