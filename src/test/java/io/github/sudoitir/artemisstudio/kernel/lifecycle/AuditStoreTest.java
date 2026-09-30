package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The audit trail is kept forever until an administrator sets a retention, and its purge is itself recorded. */
@ExtendWith(AdminAuthenticationExtension.class)
class AuditStoreTest extends PostgresIntegrationTest {

    private static final String RETENTION = "lifecycle.audit.retention";
    private static final String OLD = "AUDIT_STORE_TEST_OLD";
    private static final String RECENT = "AUDIT_STORE_TEST_RECENT";

    @Autowired
    AuditStore store;

    @Autowired
    Housekeeper housekeeper;

    @Autowired
    LifecycleRegistry registry;

    @Autowired
    SettingsService settings;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void events() {
        jdbc.update("DELETE FROM audit_event WHERE action = 'PURGE_STORE' AND target_name = 'audit'");
        for (int i = 0; i < 5; i++) {
            insert(OLD, i == 0 ? null : 1L, "60 days");
        }
        insert(RECENT, null, "1 day");
    }

    @AfterEach
    void cleanUp() {
        settings.reset(RETENTION);
        jdbc.update("DELETE FROM audit_event WHERE action IN (?, ?, 'PURGE_STORE')", OLD, RECENT);
    }

    private void insert(String action, Long parentId, String age) {
        jdbc.update(
                "INSERT INTO audit_event (ts, action, parent_id) VALUES (now() - ?::interval, ?, ?)",
                age,
                action,
                parentId);
    }

    private long count(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = ?", Long.class, action);
    }

    @Test
    void keptForeverByDefault() {
        assertThat(registry.retention("audit")).isEmpty();

        housekeeper.purgeAll();

        assertThat(count(OLD)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE action = 'PURGE_STORE' AND target_name = 'audit'",
                        Long.class))
                .isZero();
    }

    @Test
    void previewCountsWhatIsOldAndDeletesNothing() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));

        assertThat(store.preview(cutoff).rows()).isGreaterThanOrEqualTo(5);

        assertThat(count(OLD)).isEqualTo(5);
    }

    @Test
    void batchesRemoveOnlyWhatQualifiesAndFinallyReturnZero() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));
        long old = store.preview(cutoff).rows();

        long purged = 0;
        long batch;
        do {
            batch = store.purgeBatch(cutoff, 2);
            assertThat(batch).isLessThanOrEqualTo(2);
            purged += batch;
        } while (batch > 0);

        assertThat(purged).isEqualTo(old);
        assertThat(count(OLD)).isZero();
        assertThat(count(RECENT)).isEqualTo(1);
    }

    @Test
    void aConfiguredRetentionPurgesOldEventsAndRecordsOnePurge() {
        long old = store.preview(Instant.now().minus(Duration.ofDays(30))).rows();
        settings.put(RETENTION, "30d");

        housekeeper.purgeAll();

        assertThat(count(OLD)).isZero();
        assertThat(count(RECENT)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                        "SELECT affected_count FROM audit_event WHERE action = 'PURGE_STORE' AND target_name = 'audit'",
                        Long.class))
                .containsExactly(old);
    }
}
