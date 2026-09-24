package io.github.sudoitir.artemisstudio.feature.plugins;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallers;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginProperties;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.ActivationPlan;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginHost;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginRefusedException;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PurgePlan;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.StudioRestart;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Violation;
import io.github.sudoitir.artemisstudio.kernel.security.Actor;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.ReauthenticationRequiredException;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Every plugin lifecycle action an operator takes, behind the checks ADR-0103 puts in front of
 * running someone else's code in Studio, and each one audited before it acts.
 *
 * <p>The checks, in order: a browser session (never an API token or the MCP endpoint, which also
 * authenticates with a token); the installer tier; for anything that installs new code, the
 * upload kill switch; for anything but an upload, a sign-in or step-up in the last five minutes.
 */
@Service
@RequiredArgsConstructor
public class PluginAdministration {

    private final PluginHost host;
    private final PluginInstallers installers;
    private final PluginProperties properties;
    private final SessionAuthentication sessions;
    private final UserAccounts accounts;
    private final AuditService audit;
    private final ActorResolver actors;
    private final PluginAuditTrail trail;
    private final UploadRateLimit uploads;
    private final UpdateChecker updates;
    private final StudioRestart restart;

    /** Why the caller cannot install right now, or empty when they can. */
    public Optional<PluginAccessDeniedException> installBlocker() {
        Actor actor = actor();
        if (actor.tokenName() != null) {
            return Optional.of(new PluginAccessDeniedException(
                    "plugin-install-interactive-only",
                    "Plugins are installed from a signed-in browser session, never with an API token."));
        }
        if (!installers.isInstaller(actor.userId())) {
            return Optional.of(new PluginAccessDeniedException(
                    "plugin-installer-required",
                    "Only someone who can install plugins can do this; an installer can add you."));
        }
        return Optional.empty();
    }

    public boolean uploadEnabled() {
        return properties.upload().enabled();
    }

    // ---- upload and update -----------------------------------------------------------------------

    /** Validates and stores an uploaded jar as a pending upload; installs nothing. */
    public PluginHost.Inspection upload(Path jar, long bytes) throws IOException {
        requireInstaller();
        requireUploadEnabled();
        if (!uploads.tryAcquire(actor().userId())) {
            throw new PluginAccessDeniedException(
                    "plugin-upload-rate-limited",
                    "At most %d uploads an hour; try again later.".formatted(UploadRateLimit.LIMIT));
        }
        return audited("PLUGIN_UPLOAD", "upload", Map.of("bytes", bytes), () -> {
            try {
                return host.inspect(jar, actor().username());
            } catch (IOException e) {
                throw new IllegalStateException("The upload could not be read: " + e.getMessage(), e);
            }
        });
    }

    /** Asks every installed plugin's update URL for a newer version; changes nothing. */
    public List<UpdateChecker.Available> checkForUpdates() {
        requireInstaller();
        requireUploadEnabled();
        return updates.check(host.list());
    }

    /** Downloads the update {@code id}'s update URL offers and inspects it like an upload. */
    public PluginHost.Inspection downloadUpdate(String id) throws IOException {
        requireInstaller();
        requireUploadEnabled();
        var summary = host.status(id).orElseThrow(() -> notFound(id));
        return audited("PLUGIN_DOWNLOAD", id, Map.of(), () -> {
            Path jar = updates.download(summary);
            try {
                return host.inspect(jar, actor().username());
            } catch (IOException e) {
                throw new IllegalStateException("The download could not be read: " + e.getMessage(), e);
            } finally {
                jar.toFile().delete();
            }
        });
    }

    public ActivationPlan planUpload(String sha256) {
        requireInstaller();
        return host.planUpload(sha256);
    }

    public void discardUpload(String sha256) {
        requireInstaller();
        host.forgetUpload(sha256);
    }

    // ---- lifecycle ----------------------------------------------------------------------------------

    /** Installs or updates from a pending upload; the outcome arrives on the plugin's status. */
    public ActivationPlan activate(HttpServletRequest request, String sha256) {
        requireStepUp(request);
        requireUploadEnabled();
        String pluginId = host.uploadPluginId(sha256).orElse(sha256);
        return activation(
                "PLUGIN_ACTIVATE",
                pluginId,
                Map.of("sha256", sha256),
                () -> host.activateUpload(sha256, actor().username()));
    }

    public ActivationPlan enable(HttpServletRequest request, String id) {
        requireStepUp(request);
        return activation("PLUGIN_ENABLE", id, Map.of(), () -> host.enable(id, actor().username()));
    }

    public ActivationPlan rollback(HttpServletRequest request, String id) {
        requireStepUp(request);
        return activation("PLUGIN_ROLLBACK", id, Map.of(), () -> host.rollback(id, actor().username()));
    }

    public void disable(HttpServletRequest request, String id, boolean cascade) {
        requireStepUp(request);
        audited("PLUGIN_DISABLE", id, Map.of("cascade", cascade), () -> {
            host.disable(id, cascade, actor().username());
            return null;
        });
    }

    public void uninstall(HttpServletRequest request, String id, boolean cascade) {
        requireStepUp(request);
        audited("PLUGIN_UNINSTALL", id, Map.of("cascade", cascade), () -> {
            host.uninstall(id, cascade, actor().username());
            return null;
        });
    }

    /** A dry run is a read and needs no step-up; the real purge does. */
    public PurgePlan purge(HttpServletRequest request, String id, boolean dryRun) {
        if (dryRun) {
            requireInstaller();
            return host.purgePlan(id);
        }
        requireStepUp(request);
        PurgePlan plan = host.purgePlan(id);
        return audited("PLUGIN_PURGE", id, Map.of("schema", plan.schema()), () -> {
            host.purge(id, actor().username());
            return plan;
        });
    }

    // ---- restart ----------------------------------------------------------------------------------

    /**
     * Restarts Studio now, for a plugin waiting on a restart or a runtime that did not unload
     * (ADR-0104). Everyone signed in loses the connection for as long as Studio takes to start.
     */
    public void restartStudio(HttpServletRequest request) {
        requireStepUp(request);
        audited("STUDIO_RESTART", "studio", Map.of(), () -> {
            restart.restartOnRequest("requested by " + actor().username());
            return null;
        });
    }

    // ---- installers -----------------------------------------------------------------------------------

    public record InstallerView(UUID userId, String username, java.time.Instant grantedAt, String grantedBy) {}

    public List<InstallerView> installers() {
        requireInstaller();
        return installers.list().stream()
                .map(i -> new InstallerView(
                        i.userId(),
                        accounts.byId(i.userId())
                                .map(UserAccounts.Account::username)
                                .orElse("(deleted user)"),
                        i.grantedAt(),
                        i.grantedBy()))
                .toList();
    }

    public void grantInstaller(HttpServletRequest request, String username) {
        requireStepUp(request);
        UserAccounts.Account account = accounts.byUsername(username)
                .orElseThrow(() -> new PluginRefusedException(List.of(
                        new Violation("not-found", "No user named '" + username + "'.", "Check the username."))));
        audited("PLUGIN_INSTALLER_GRANT", username, Map.of(), () -> {
            installers.grant(account.id(), actor().username());
            return null;
        });
    }

    public void revokeInstaller(HttpServletRequest request, UUID userId) {
        requireStepUp(request);
        String username =
                accounts.byId(userId).map(UserAccounts.Account::username).orElse(userId.toString());
        audited("PLUGIN_INSTALLER_REVOKE", username, Map.of(), () -> {
            if (!installers.revoke(userId)) {
                throw new PluginRefusedException(List.of(new Violation(
                        "last-installer",
                        "'" + username + "' is the only one who can install plugins.",
                        "Add another installer first.")));
            }
            return null;
        });
    }

    // ---- checks and audit ------------------------------------------------------------------------------

    private void requireInstaller() {
        installBlocker().ifPresent(e -> {
            throw e;
        });
    }

    private void requireStepUp(HttpServletRequest request) {
        requireInstaller();
        if (!sessions.recentlyAuthenticated(request)) {
            throw new ReauthenticationRequiredException();
        }
    }

    private Actor actor() {
        return actors.resolve();
    }

    private void requireUploadEnabled() {
        if (!uploadEnabled()) {
            throw new PluginAccessDeniedException(
                    "plugin-upload-disabled",
                    "Installing and updating plugins is switched off (artemis-studio.plugins.upload.enabled=false).");
        }
    }

    private static PluginRefusedException notFound(String id) {
        return new PluginRefusedException(List.of(new Violation("not-found", "No installed plugin '" + id + "'.", "")));
    }

    /** Audits a synchronous action: the row is committed before it runs and finished with its outcome. */
    private <T> T audited(String action, String target, Map<String, ?> params, Supplier<T> work) {
        Actor actor = actors.resolve();
        AuditEvent event = audit.begin(actor, action, "plugin", target, null, null, params, false);
        try {
            T result = work.get();
            audit.succeed(event, 1);
            return result;
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    /** Audits an activation, whose outcome the host reports later, from its own thread. */
    private ActivationPlan activation(
            String action, String pluginId, Map<String, ?> params, Supplier<ActivationPlan> start) {
        AuditEvent event = audit.begin(actors.resolve(), action, "plugin", pluginId, null, null, params, false);
        trail.activationBegan(pluginId, event);
        try {
            return start.get();
        } catch (RuntimeException e) {
            trail.activationRefused(pluginId);
            audit.fail(event, e.getMessage());
            throw e;
        }
    }
}
