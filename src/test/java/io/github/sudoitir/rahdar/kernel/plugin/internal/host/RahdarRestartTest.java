package io.github.sudoitir.rahdar.kernel.plugin.internal.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.sudoitir.rahdar.kernel.plugin.PluginProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/** Rahdar stops itself only where something starts it again, and never in a loop (ADR-0104). */
class RahdarRestartTest {

    private static PluginProperties supervised(Boolean value) {
        return new PluginProperties(null, false, null, null, null, new PluginProperties.Restart(value), null);
    }

    private static RahdarRestart restart(
            PluginProperties properties, MockEnvironment env, Clock clock, CompletableFuture<Integer> exited) {
        return new RahdarRestart(mock(ConfigurableApplicationContext.class), properties, env, clock, exited::complete);
    }

    @Test
    void unsupervisedByDefaultAndSupervisedOnKubernetesOrWhenConfigured() {
        Clock clock = Clock.systemUTC();
        assertThat(restart(supervised(null), new MockEnvironment(), clock, new CompletableFuture<>())
                        .supervised())
                .isFalse();
        // Boot's own switch; on a real cluster CloudPlatform detects KUBERNETES_SERVICE_HOST/PORT itself.
        var kubernetes = new MockEnvironment().withProperty("spring.main.cloud-platform", "kubernetes");
        assertThat(restart(supervised(null), kubernetes, clock, new CompletableFuture<>())
                        .supervised())
                .isTrue();
        assertThat(restart(supervised(false), kubernetes, clock, new CompletableFuture<>())
                        .supervised())
                .isFalse();
        assertThat(restart(supervised(true), new MockEnvironment(), clock, new CompletableFuture<>())
                        .supervised())
                .isTrue();
    }

    @Test
    void anUnsupervisedRahdarRefusesToStopItself() {
        var exited = new CompletableFuture<Integer>();
        var restart = restart(supervised(false), new MockEnvironment(), Clock.systemUTC(), exited);
        assertThatThrownBy(() -> restart.restart("test"))
                .isInstanceOf(PluginRefusedException.class)
                .hasMessageContaining("will not stop itself");
        assertThat(exited).isNotDone();
    }

    @Test
    void aManualRestartIsRefusedSoonAfterBoot() {
        var clock = Clock.fixed(Instant.parse("2026-09-23T10:00:00Z"), ZoneOffset.UTC);
        var restart = restart(supervised(true), new MockEnvironment(), clock, new CompletableFuture<>());
        assertThatThrownBy(() -> restart.restartOnRequest("test"))
                .isInstanceOf(PluginRefusedException.class)
                .hasMessageContaining("less than");
        assertThat(restart.manualRestartAllowedAt()).contains(clock.instant().plus(RahdarRestart.MIN_UPTIME));
    }

    @Test
    void aSupervisedRestartExitsOnceWithANonZeroCode() throws Exception {
        var exited = new CompletableFuture<Integer>();
        var clock = Clock.offset(Clock.systemUTC(), Duration.ZERO);
        var restart = restart(supervised(true), new MockEnvironment(), clock, exited);

        restart.restart("test");
        restart.restart("again");

        assertThat(restart.restarting()).isTrue();
        assertThat(exited.get(RahdarRestart.DELAY.toSeconds() + 5, TimeUnit.SECONDS))
                .isEqualTo(RahdarRestart.EXIT_CODE)
                .isNotZero();
    }
}
