package io.github.sudoitir.artemisstudio.feature.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallers;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginProperties;
import io.github.sudoitir.artemisstudio.kernel.plugin.SemVer;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginHost;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.StudioRestart;
import io.github.sudoitir.artemisstudio.kernel.security.Actor;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The checks in front of plugin administration that need no database (ADR-0103). */
class PluginAdministrationTest {

    private final PluginHost host = mock(PluginHost.class);
    private final PluginInstallers installers = mock(PluginInstallers.class);
    private final AuditService audit = mock(AuditService.class);
    private final UUID userId = UUID.randomUUID();
    private final ActorResolver actors = mock(ActorResolver.class);

    private PluginAdministration administration(boolean uploadEnabled) {
        return new PluginAdministration(
                host,
                installers,
                new PluginProperties(null, false, null, null, new PluginProperties.Upload(uploadEnabled), null),
                mock(SessionAuthentication.class),
                mock(UserAccounts.class),
                audit,
                actors,
                new PluginAuditTrail(audit, mock(org.springframework.beans.factory.ObjectProvider.class)),
                new UploadRateLimit(Clock.systemUTC()),
                new UpdateChecker(JsonMapper.builder().build()),
                mock(StudioRestart.class));
    }

    @Test
    void theKillSwitchRefusesAnUploadBeforeAnythingIsRead() {
        when(installers.isInstaller(userId)).thenReturn(true);
        when(actors.resolve()).thenReturn(new Actor("ops", null, null, userId));

        assertThatThrownBy(() -> administration(false).upload(Path.of("unused.jar"), 1))
                .isInstanceOf(PluginAccessDeniedException.class)
                .extracting(e -> ((PluginAccessDeniedException) e).slug())
                .isEqualTo("plugin-upload-disabled");
        verifyNoInteractions(host, audit);
    }

    @Test
    void anApiTokenIsRefusedEvenForAnInstaller() {
        when(installers.isInstaller(userId)).thenReturn(true);
        when(actors.resolve()).thenReturn(new Actor("ops", null, null, userId, "ci"));

        assertThat(administration(true).installBlocker())
                .get()
                .extracting(PluginAccessDeniedException::slug)
                .isEqualTo("plugin-install-interactive-only");
    }

    @Test
    void theSixthUploadInAnHourIsRefused() {
        var limit = new UploadRateLimit(Clock.systemUTC());
        for (int i = 0; i < UploadRateLimit.LIMIT; i++) {
            assertThat(limit.tryAcquire(userId)).isTrue();
        }
        assertThat(limit.tryAcquire(userId)).isFalse();
        assertThat(limit.tryAcquire(UUID.randomUUID())).as("per user").isTrue();
    }

    @Test
    void updatesAreOnlyFetchedOverHttps() {
        assertThatThrownBy(() -> UpdateChecker.requireHttps("http://updates.example/acme.json"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> UpdateChecker.requireHttps("file:///etc/passwd"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> UpdateChecker.requireHttps("https://user:pw@updates.example/x"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void versionsOrderNumericallyAndAPreReleaseBeforeItsRelease() {
        assertThat(SemVer.compare("1.10.0", "1.9.9")).isPositive();
        assertThat(SemVer.compare("1.0.0-rc.1", "1.0.0")).isNegative();
        assertThat(SemVer.compare("1.0", "1.0.0")).isZero();
        assertThat(SemVer.compare("2.0.0+build.5", "2.0.0")).isZero();
    }
}
