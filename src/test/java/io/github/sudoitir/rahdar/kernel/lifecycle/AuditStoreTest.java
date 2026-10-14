package io.github.sudoitir.rahdar.kernel.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.kernel.audit.AuditHold;
import io.github.sudoitir.rahdar.kernel.audit.AuditReceiver;
import io.github.sudoitir.rahdar.kernel.audit.AuditRetentionHold;
import io.github.sudoitir.rahdar.kernel.auditfeed.AuditFeedSettings;
import io.github.sudoitir.rahdar.kernel.auditfeed.AuditPurgeRefused;
import io.github.sudoitir.rahdar.kernel.auditfeed.AuditReceiverFixtures;
import io.github.sudoitir.rahdar.kernel.auditfeed.AuditReceiverFixtures.FakeHandle;
import io.github.sudoitir.rahdar.kernel.auditfeed.AuditReceiverFixtures.TestHold;
import io.github.sudoitir.rahdar.kernel.auditfeed.AuditReceiverFixtures.TestReceiver;
import io.github.sudoitir.rahdar.kernel.auditfeed.AuditReceivers;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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

    @Autowired
    AuditReceivers receivers;

    @Autowired
    PurgeStatus purgeStatus;

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
        settings.reset(AuditFeedSettings.MAX_UNACKNOWLEDGED_AGE);
        jdbc.update("DELETE FROM audit_receiver WHERE plugin_id LIKE 'audit-store-test-%'");
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

    private FakeHandle holder(TestHold hold) {
        String id = "audit-store-test-hold";
        AuditReceiverFixtures.installHolder(jdbc, id);
        FakeHandle handle = new FakeHandle(id, false, true, Map.of(AuditRetentionHold.class, hold));
        receivers.attach(handle);
        return handle;
    }

    private void release(FakeHandle handle) {
        receivers.detach(handle);
        AuditReceiverFixtures.removeHolder(jdbc, handle.id());
    }

    private long purgeAll() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));
        long purged = 0;
        long batch;
        do {
            batch = store.purgeBatch(cutoff, 100);
            purged += batch;
        } while (batch > 0);
        return purged;
    }

    @Test
    void aHeldRecordIsLeftAloneWhateverTheRetention() {
        TestHold hold = new TestHold();
        hold.holds = java.util.List.of(new AuditHold(null, null, null, null, OLD, null, null));
        FakeHandle handle = holder(hold);
        try {
            purgeAll();

            assertThat(count(OLD)).isEqualTo(5);

            hold.holds = java.util.List.of();
            purgeAll();

            assertThat(count(OLD)).isZero();
        } finally {
            release(handle);
        }
    }

    @Test
    void aHoldThatThrowsRefusesThePurgeAndTheRunReportsWhy() {
        TestHold hold = new TestHold();
        hold.failure = new IllegalStateException("evidence store offline");
        FakeHandle handle = holder(hold);
        try {
            settings.put(RETENTION, "30d");

            assertThatThrownBy(this::purgeAll)
                    .isInstanceOf(AuditPurgeRefused.class)
                    .hasMessageContaining("audit-store-test-hold")
                    .hasMessageContaining("evidence store offline");
            housekeeper.purgeAll();

            assertThat(count(OLD)).isEqualTo(5);
            assertThat(purgeStatus.all().get("audit").error()).contains("evidence store offline");
        } finally {
            release(handle);
        }
    }

    @Test
    void aHoldPluginThatIsNotRunningHereRefusesThePurge() {
        AuditReceiverFixtures.installHolder(jdbc, "audit-store-test-hold");
        try {
            assertThatThrownBy(this::purgeAll)
                    .isInstanceOf(AuditPurgeRefused.class)
                    .hasMessageContaining("not running on this replica");
            assertThat(count(OLD)).isEqualTo(5);
        } finally {
            AuditReceiverFixtures.removeHolder(jdbc, "audit-store-test-hold");
        }
    }

    @Test
    void aHoldThatNamesTooManyRefusesThePurge() {
        TestHold hold = new TestHold();
        hold.holds = java.util.Collections.nCopies(201, new AuditHold(null, null, null, null, "NOTHING", null, null));
        FakeHandle handle = holder(hold);
        try {
            assertThatThrownBy(this::purgeAll)
                    .isInstanceOf(AuditPurgeRefused.class)
                    .hasMessageContaining("201");
            assertThat(count(OLD)).isEqualTo(5);
        } finally {
            release(handle);
        }
    }

    @Test
    void recordsAReceiverHasNotAcknowledgedAreKeptUntilTheMaximumAgeThenPurgedWithAGap() {
        String pluginId = "audit-store-test-receiver";
        FakeHandle handle = new FakeHandle(pluginId, true, false, Map.of(AuditReceiver.class, new TestReceiver()));
        receivers.attach(handle);
        try {
            settings.put(AuditFeedSettings.MAX_UNACKNOWLEDGED_AGE, "90d");

            purgeAll();

            assertThat(count(OLD)).isEqualTo(5);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM audit_receiver_gap WHERE plugin_id = ?", Long.class, pluginId))
                    .isZero();

            settings.put(AuditFeedSettings.MAX_UNACKNOWLEDGED_AGE, "45d");
            purgeAll();

            assertThat(count(OLD)).isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT sum(count) FROM audit_receiver_gap WHERE plugin_id = ? AND delivered_at IS NULL",
                            Long.class,
                            pluginId))
                    .isGreaterThanOrEqualTo(5);
        } finally {
            receivers.detach(handle);
        }
    }
}
