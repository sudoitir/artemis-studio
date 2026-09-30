package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link IdempotencyStore} against a real Postgres: keys older than the cutoff go, newer ones stay. */
class IdempotencyStoreTest extends PostgresIntegrationTest {

    private static final UUID USER = UUID.randomUUID();

    @Autowired
    IdempotencyStore store;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM idempotency_record WHERE user_id = ?", USER);
    }

    private void key(String key, Duration age) {
        jdbc.update(
                "INSERT INTO idempotency_record (user_id, idem_key, fingerprint, state, created_at)"
                        + " VALUES (?, ?, 'f', 'DONE', now() - make_interval(secs => ?))",
                USER,
                key,
                age.toSeconds());
    }

    private int keys() {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE user_id = ?", Integer.class, USER);
    }

    @Test
    void purgeRemovesExpiredKeysOnly() {
        key("old-1", Duration.ofHours(30));
        key("old-2", Duration.ofHours(25));
        key("fresh", Duration.ofHours(1));
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));

        assertThat(store.preview(cutoff).rows()).isEqualTo(2);
        assertThat(store.purgeBatch(cutoff, 100)).isEqualTo(2);

        assertThat(keys()).isEqualTo(1);
        assertThat(store.purgeBatch(cutoff, 100)).isZero();
    }

    @Test
    void theStoreKeepsAtLeastTheDayAKeyReplays() {
        assertThat(store.def().defaultRetention()).isEqualTo(Duration.ofHours(24));
        assertThat(store.def().minRetention()).isEqualTo(Duration.ofHours(24));
        assertThat(store.def().tables()).containsExactly("idempotency_record");
    }
}
