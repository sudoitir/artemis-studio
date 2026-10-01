package io.github.sudoitir.artemisstudio.kernel.plugin.internal.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.core.StudioHealth;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginLicenseStore;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginLicenseStore.State;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginLicenseStore.Summary;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

/** The {@code pluginLicenses} contributor degrades for the plugins that need attention and no others. */
class PluginLicensesHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    private final PluginLicenseStore store = mock(PluginLicenseStore.class);
    private final PluginLicensesHealthIndicator indicator =
            new PluginLicensesHealthIndicator(store, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Summary summary(State state, Instant uploadedAt) {
        return new Summary(state, null, null, null, uploadedAt, "ops", null);
    }

    @Test
    void isUpWhenEveryLicenseIsValidOrNoPluginNeedsOne() {
        when(store.activeDeclaring()).thenReturn(Map.of("acme-a", summary(State.VALID, NOW)));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

        when(store.activeDeclaring()).thenReturn(Map.of());
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void isDegradedNamingEachPluginAndItsState() {
        when(store.activeDeclaring())
                .thenReturn(Map.of(
                        "acme-a", PluginLicenseStore.missing(),
                        "acme-b", summary(State.EXPIRING, NOW),
                        "acme-c", summary(State.EXPIRED, NOW),
                        "acme-d", summary(State.OVER_LIMIT, NOW),
                        "acme-e", summary(State.INVALID, NOW),
                        "acme-f", summary(State.VALID, NOW)));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(StudioHealth.DEGRADED);
        assertThat(health.getDetails())
                .containsEntry(
                        "plugins",
                        Map.of(
                                "acme-a", "MISSING",
                                "acme-b", "EXPIRING",
                                "acme-c", "EXPIRED",
                                "acme-d", "OVER_LIMIT",
                                "acme-e", "INVALID"));
    }

    @Test
    void aFileWaitsFiveMinutesForItsPluginsVerdict() {
        when(store.activeDeclaring()).thenReturn(Map.of("acme-a", summary(State.UNCHECKED, NOW.minusSeconds(120))));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

        when(store.activeDeclaring()).thenReturn(Map.of("acme-a", summary(State.UNCHECKED, NOW.minusSeconds(301))));
        assertThat(indicator.health().getStatus()).isEqualTo(StudioHealth.DEGRADED);
    }
}
