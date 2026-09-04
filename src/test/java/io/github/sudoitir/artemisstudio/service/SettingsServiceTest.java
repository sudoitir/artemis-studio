package io.github.sudoitir.artemisstudio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.persist.MetricSampleReaper;
import io.github.sudoitir.artemisstudio.persist.StudioSettingRepository;
import io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link SettingsService}: default fall-through, put-then-get, validation, and live limiter/reaper wiring. */
class SettingsServiceTest extends PostgresIntegrationTest {

    @Autowired
    SettingsService settings;

    @Autowired
    StudioSettingRepository repo;

    @Autowired
    NodeCallLimiter limiter;

    @Autowired
    MetricSampleReaper reaper;

    @AfterEach
    void cleanUp() {
        repo.deleteAll();
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
        assertThat(settings.tierBMillis()).isEqualTo(30_000L);
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
}
