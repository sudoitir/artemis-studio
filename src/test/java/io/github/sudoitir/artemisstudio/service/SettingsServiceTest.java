package io.github.sudoitir.artemisstudio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditEventRepository;
import io.github.sudoitir.artemisstudio.persist.MetricSampleReaper;
import io.github.sudoitir.artemisstudio.persist.StudioSettingRepository;
import io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link SettingsService}: default fall-through, put-then-get, validation, and live limiter/reaper wiring. */
@ExtendWith(AdminAuthenticationExtension.class)
class SettingsServiceTest extends PostgresIntegrationTest {

    @Autowired
    SettingsService settings;

    @Autowired
    StudioSettingRepository repo;

    @Autowired
    NodeCallLimiter limiter;

    @Autowired
    MetricSampleReaper reaper;

    @Autowired
    AuditEventRepository auditEvents;

    @AfterEach
    void cleanUp() {
        repo.deleteAll();
        auditEvents.deleteAll();
        settings.applyRuntime();
    }

    @Test
    void unsetKeysFallThroughToTheApplicationYmlDefaults() {
        assertThat(settings.tierA()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.metricRetentionDays()).isEqualTo(7);
        assertThat(settings.effective().get(SettingsService.TIER_A).overridden())
                .isFalse();
    }

    @Test
    void putThenGetReturnsTheOverrideAndFlagsIt() {
        settings.put(SettingsService.TIER_B, "30s");
        settings.put(SettingsService.RETENTION_DAYS, "3");

        assertThat(settings.tierB()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.metricRetentionDays()).isEqualTo(3);
        assertThat(settings.effective().get(SettingsService.TIER_B).overridden())
                .isTrue();
        assertThat(settings.effective().get(SettingsService.TIER_B).defaultValue())
                .isEqualTo("PT15S");
    }

    @Test
    void settingTheLimiterAndRetentionAppliesToTheLiveHolders() {
        settings.put(SettingsService.RATE_LIMIT, "9");
        settings.put(SettingsService.RETENTION_DAYS, "2");

        assertThat(limiter.permitsPerSecond()).isEqualTo(9);
        assertThat(reaper.retentionDays()).isEqualTo(2);
    }

    @Test
    void resetClearsTheOverride() {
        settings.put(SettingsService.RATE_LIMIT, "9");
        settings.reset(SettingsService.RATE_LIMIT);

        assertThat(settings.limiterPermits()).isEqualTo(20);
        assertThat(settings.effective().get(SettingsService.RATE_LIMIT).overridden())
                .isFalse();
    }

    @Test
    void invalidValuesAreRejected() {
        assertThatThrownBy(() -> settings.put(SettingsService.TIER_A, "0s"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.put(SettingsService.RATE_LIMIT, "0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.put("bogus.key", "1")).isInstanceOf(IllegalArgumentException.class);
    }

    /** One key of each {@link SettingsService.Kind}, so a new kind cannot land untested. */
    @Test
    void everyKindRoundTrips() {
        settings.put(SettingsService.BROKER_READ_TIMEOUT, "45s");
        settings.put(SettingsService.ALERTING_MAX_ATTEMPTS, "9");
        settings.put(SettingsService.METRIC_REAPER_CRON, "0 45 4 * * *");

        assertThat(settings.brokerReadTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(settings.alertingMaxAttempts()).isEqualTo(9);
        assertThat(settings.metricReaperCron()).isEqualTo("0 45 4 * * *");
        assertThat(settings.effective().get(SettingsService.METRIC_REAPER_CRON).kind())
                .isEqualTo("CRON");
    }

    /**
     * A cron that fires more often than once a minute is refused, not clamped. These
     * schedules drive bulk deletes and DDL, so a stray seconds field turning a nightly
     * trim into a per-second one has to fail loudly at the point it is typed.
     */
    @Test
    void aCronThatFiresTooOftenIsRejected() {
        assertThatThrownBy(() -> settings.put(SettingsService.METRIC_REAPER_CRON, "* * * * * *"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.put(SettingsService.METRIC_REAPER_CRON, "not a cron"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.metricReaperCron()).isEqualTo("0 30 3 * * *");
    }

    /** Every key is described well enough for the settings screen to render it unaided. */
    @Test
    void everyKeyCarriesItsOwnFormMetadata() {
        assertThat(settings.effective()).allSatisfy((key, value) -> {
            assertThat(value.group()).as("group of %s", key).isNotBlank();
            assertThat(value.label()).as("label of %s", key).isNotBlank();
            assertThat(value.hint()).as("hint of %s", key).isNotBlank();
            assertThat(value.kind()).as("kind of %s", key).isNotBlank();
        });
    }

    @Test
    void changingASettingIsAudited() {
        settings.put(SettingsService.BULK_CAP, "50");
        settings.reset(SettingsService.BULK_CAP);

        // Sorted by the generated id rather than trusting findAll()'s order: an
        // unordered SELECT may return either row first, and asserting a sequence on
        // it passes or fails by luck. The id is monotonic, so this still pins that
        // the update was recorded before the reset.
        assertThat(auditEvents.findAll().stream()
                        .sorted(java.util.Comparator.comparing(AuditEventEntity::getId))
                        .toList())
                .extracting(AuditEventEntity::getAction, AuditEventEntity::getTargetName)
                .containsExactly(
                        tuple("UPDATE_SETTING", SettingsService.BULK_CAP),
                        tuple("RESET_SETTING", SettingsService.BULK_CAP));
    }

    /**
     * A rejected value is not an audit event: validation runs before the write, so
     * nothing was changed and there is no transaction to record. This pins that,
     * because the tempting alternative — audit first, then validate — records a
     * change that then rolls back with the rest of the transaction.
     */
    @Test
    void aRejectedChangeWritesNoAuditRow() {
        assertThatThrownBy(() -> settings.put(SettingsService.BULK_CAP, "0"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(auditEvents.findAll()).isEmpty();
    }
}
