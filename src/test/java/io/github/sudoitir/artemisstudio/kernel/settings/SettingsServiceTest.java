package io.github.sudoitir.artemisstudio.kernel.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.sudoitir.artemisstudio.feature.alerting.AlertingSettings;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditEventEntity;
import io.github.sudoitir.artemisstudio.kernel.audit.internal.AuditEventRepository;
import io.github.sudoitir.artemisstudio.kernel.settings.internal.StudioSettingRepository;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerSettings;
import io.github.sudoitir.artemisstudio.platform.broker.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.platform.scrape.MetricSampleReaper;
import io.github.sudoitir.artemisstudio.platform.scrape.ScrapeSettings;
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
        assertThat(settings.duration(ScrapeSettings.TIER_A)).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.intValue(ScrapeSettings.METRIC_RETENTION_DAYS)).isEqualTo(7);
        assertThat(settings.effective().get(ScrapeSettings.TIER_A).overridden()).isFalse();
    }

    @Test
    void putThenGetReturnsTheOverrideAndFlagsIt() {
        settings.put(ScrapeSettings.TIER_B, "30s");
        settings.put(ScrapeSettings.METRIC_RETENTION_DAYS, "3");

        assertThat(settings.duration(ScrapeSettings.TIER_B)).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.intValue(ScrapeSettings.METRIC_RETENTION_DAYS)).isEqualTo(3);
        assertThat(settings.effective().get(ScrapeSettings.TIER_B).overridden()).isTrue();
        assertThat(settings.effective().get(ScrapeSettings.TIER_B).defaultValue())
                .isEqualTo("PT15S");
    }

    @Test
    void settingTheLimiterAndRetentionAppliesToTheLiveHolders() {
        settings.put(BrokerSettings.RATE_LIMIT, "9");
        settings.put(ScrapeSettings.METRIC_RETENTION_DAYS, "2");

        assertThat(limiter.permitsPerSecond()).isEqualTo(9);
        assertThat(reaper.retentionDays()).isEqualTo(2);
    }

    @Test
    void resetClearsTheOverride() {
        settings.put(BrokerSettings.RATE_LIMIT, "9");
        settings.reset(BrokerSettings.RATE_LIMIT);

        assertThat(settings.intValue(BrokerSettings.RATE_LIMIT)).isEqualTo(20);
        assertThat(settings.effective().get(BrokerSettings.RATE_LIMIT).overridden())
                .isFalse();
    }

    @Test
    void invalidValuesAreRejected() {
        assertThatThrownBy(() -> settings.put(ScrapeSettings.TIER_A, "0s"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.put(BrokerSettings.RATE_LIMIT, "0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.put("bogus.key", "1")).isInstanceOf(IllegalArgumentException.class);
    }

    /** One key of each {@link SettingDef.Kind}, so a new kind cannot land untested. */
    @Test
    void everyKindRoundTrips() {
        settings.put(BrokerSettings.READ_TIMEOUT, "45s");
        settings.put(AlertingSettings.MAX_ATTEMPTS, "9");
        settings.put(ScrapeSettings.METRIC_REAPER_CRON, "0 45 4 * * *");

        assertThat(settings.duration(BrokerSettings.READ_TIMEOUT)).isEqualTo(Duration.ofSeconds(45));
        assertThat(settings.intValue(AlertingSettings.MAX_ATTEMPTS)).isEqualTo(9);
        assertThat(settings.value(ScrapeSettings.METRIC_REAPER_CRON)).isEqualTo("0 45 4 * * *");
        assertThat(settings.effective().get(ScrapeSettings.METRIC_REAPER_CRON).kind())
                .isEqualTo("CRON");
    }

    /**
     * A cron that fires more often than once a minute is refused, not clamped. These
     * schedules drive bulk deletes and DDL, so a stray seconds field turning a nightly
     * trim into a per-second one has to fail loudly at the point it is typed.
     */
    @Test
    void aCronThatFiresTooOftenIsRejected() {
        assertThatThrownBy(() -> settings.put(ScrapeSettings.METRIC_REAPER_CRON, "* * * * * *"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.put(ScrapeSettings.METRIC_REAPER_CRON, "not a cron"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.value(ScrapeSettings.METRIC_REAPER_CRON)).isEqualTo("0 30 3 * * *");
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
        settings.put(BrokerSettings.BULK_CAP, "50");
        settings.reset(BrokerSettings.BULK_CAP);

        // Sorted by the generated id rather than trusting findAll()'s order: an
        // unordered SELECT may return either row first, and asserting a sequence on
        // it passes or fails by luck. The id is monotonic, so this still pins that
        // the update was recorded before the reset.
        assertThat(auditEvents.findAll().stream()
                        .sorted(java.util.Comparator.comparing(AuditEventEntity::getId))
                        .toList())
                .extracting(AuditEventEntity::getAction, AuditEventEntity::getTargetName)
                .containsExactly(
                        tuple("UPDATE_SETTING", BrokerSettings.BULK_CAP),
                        tuple("RESET_SETTING", BrokerSettings.BULK_CAP));
    }

    /**
     * A rejected value is not an audit event: validation runs before the write, so
     * nothing was changed and there is no transaction to record. This pins that,
     * because the tempting alternative — audit first, then validate — records a
     * change that then rolls back with the rest of the transaction.
     */
    @Test
    void aRejectedChangeWritesNoAuditRow() {
        assertThatThrownBy(() -> settings.put(BrokerSettings.BULK_CAP, "0"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(auditEvents.findAll()).isEmpty();
    }
}
