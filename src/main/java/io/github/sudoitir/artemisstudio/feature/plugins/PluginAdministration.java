package io.github.sudoitir.artemisstudio.feature.plugins;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.gate.Gated;
import io.github.sudoitir.artemisstudio.kernel.gate.Operation;
import io.github.sudoitir.artemisstudio.kernel.gate.OperationGate;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallers;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginLicenseStore;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginProperties;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.ActivationPlan;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginHost;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginRefusedException;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginSummary;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PurgePlan;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.StudioRestart;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.PluginTrust;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.PublisherKeys;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.TrustDecision;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Signer;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Violation;
import io.github.sudoitir.artemisstudio.kernel.security.Actor;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.ReauthenticationRequiredException;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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

    private static final String UPLOAD = "upload";

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
    private final PluginTrust trust;
    private final PluginLicenseStore licenses;
    private final OperationGate gate;

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
    public PluginHost.Inspection upload(Path jar, long bytes) {
        requireInstaller();
        requireUploadEnabled();
        if (!uploads.tryAcquire(actor().userId())) {
            throw new PluginAccessDeniedException(
                    "plugin-upload-rate-limited",
                    "At most %d uploads an hour; try again later.".formatted(UploadRateLimit.LIMIT));
        }
        return audited("PLUGIN_UPLOAD", UPLOAD, Map.of("bytes", bytes), () -> {
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
    public PluginHost.Inspection downloadUpdate(String id) {
        requireInstaller();
        requireUploadEnabled();
        var summary = host.status(id).orElseThrow(() -> notFound("No installed plugin '" + id + "'."));
        return audited("PLUGIN_DOWNLOAD", id, Map.of(), () -> {
            Path jar = updates.download(summary);
            try {
                return host.inspect(jar, actor().username());
            } catch (IOException e) {
                throw new IllegalStateException("The download could not be read: " + e.getMessage(), e);
            } finally {
                deleteQuietly(jar);
            }
        });
    }

    /** The downloaded jar is a private temp file: failing to remove it must not fail the inspection. */
    private static void deleteQuietly(Path jar) {
        try {
            Files.deleteIfExists(jar);
        } catch (IOException _) {
            // Best effort: a failed delete must not turn a finished inspection into a failure.
        }
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

    /**
     * Installs or updates from a pending upload; the outcome arrives on the plugin's status. A held request
     * keeps only the jar's sha256: the bytes stay in the plugin artifact store, and the replay refuses when the
     * upload is no longer pending.
     */
    @Gated("plugin.activate-upload")
    public ActivationPlan activate(String sha256, boolean acknowledge) {
        requireInstaller();
        requireUploadEnabled();
        String pluginId = host.uploadPluginId(sha256).orElse(sha256);
        return gate.run(
                Operation.of(new PluginOperations.ActivateUpload(sha256, acknowledge)),
                () -> activation(
                        "PLUGIN_ACTIVATE",
                        pluginId,
                        withTrust(Map.of("sha256", sha256), sha256),
                        () -> host.activateUpload(sha256, actor().username(), acknowledge)));
    }

    @Gated("plugin.enable")
    public ActivationPlan enable(String id, boolean acknowledge) {
        requireInstaller();
        return gate.run(Operation.of(new PluginOperations.EnablePlugin(id, acknowledge)), () -> {
            String sha256 = host.status(id).map(PluginSummary::sha256).orElse(null);
            return activation(
                    "PLUGIN_ENABLE",
                    id,
                    withTrust(Map.of(), sha256),
                    () -> host.enable(id, actor().username(), acknowledge));
        });
    }

    @Gated("plugin.rollback")
    public ActivationPlan rollback(String id, boolean acknowledge) {
        requireInstaller();
        return gate.run(Operation.of(new PluginOperations.RollbackPlugin(id, acknowledge)), () -> {
            String sha256 = host.status(id).map(PluginSummary::previousSha256).orElse(null);
            return activation(
                    "PLUGIN_ROLLBACK",
                    id,
                    withTrust(Map.of(), sha256),
                    () -> host.rollback(id, actor().username(), acknowledge));
        });
    }

    @Gated("plugin.disable")
    public void disable(String id, boolean cascade) {
        requireInstaller();
        gate.run(
                Operation.of(new PluginOperations.DisablePlugin(id, cascade)),
                () -> audited("PLUGIN_DISABLE", id, Map.of("cascade", cascade), () -> {
                    host.disable(id, cascade, actor().username());
                    return null;
                }));
    }

    @Gated("plugin.uninstall")
    public void uninstall(String id, boolean cascade) {
        requireInstaller();
        gate.run(
                Operation.of(new PluginOperations.UninstallPlugin(id, cascade)),
                () -> audited("PLUGIN_UNINSTALL", id, Map.of("cascade", cascade), () -> {
                    host.uninstall(id, cascade, actor().username());
                    return null;
                }));
    }

    /** A dry run is a read, is never gated and needs no step-up; the real purge does (the caller demands it). */
    @Gated("plugin.purge")
    public PurgePlan purge(String id, boolean dryRun) {
        requireInstaller();
        if (dryRun) {
            return host.purgePlan(id);
        }
        return gate.run(Operation.of(new PluginOperations.PurgePlugin(id)), () -> {
            PurgePlan plan = host.purgePlan(id);
            return audited("PLUGIN_PURGE", id, Map.of("schema", plan.schema()), () -> {
                host.purge(id, actor().username());
                return plan;
            });
        });
    }

    // ---- license files (ADR-0153) ---------------------------------------------------------------

    /**
     * Stores or replaces the plugin's license file. The audit row opens first, so a refusal is
     * recorded too, with the plugin, the file's hash and its size and never its content. The installer
     * tier and the {@code stepUp} check are demanded before the request reaches the gate, since they are
     * bound to the request: the web layer passes its request's check, and a replay passes none. The other
     * gated actions below do the same.
     */
    @Gated("plugin.license.put")
    public void uploadLicense(String id, byte[] content, Runnable stepUp) {
        String sha256 = PluginLicenseStore.sha256(content);
        Map<String, Object> params = Map.of("size", content.length, "sha256", sha256);
        checkedFirst("PLUGIN_LICENSE_UPLOAD", id, params, stepUp, () -> {});
        gate.run(
                Operation.of(new PluginOperations.PutLicense(
                        id, Base64.getEncoder().encodeToString(content), sha256, content.length)),
                () -> audited("PLUGIN_LICENSE_UPLOAD", id, params, () -> {
                    requireInstaller();
                    licenses.put(id, content, actor().username());
                    return null;
                }));
    }

    @Gated("plugin.license.delete")
    public void removeLicense(String id, Runnable stepUp) {
        checkedFirst("PLUGIN_LICENSE_REMOVE", id, Map.of(), stepUp, () -> {});
        gate.run(
                Operation.of(new PluginOperations.DeleteLicense(id)),
                () -> audited("PLUGIN_LICENSE_REMOVE", id, Map.of(), () -> {
                    requireInstaller();
                    licenses.remove(id);
                    return null;
                }));
    }

    // ---- restart ----------------------------------------------------------------------------------

    /**
     * Restarts Studio now, for a plugin waiting on a restart or a runtime that did not unload
     * (ADR-0104). Everyone signed in loses the connection for as long as Studio takes to start.
     */
    public void restartStudio() {
        requireInstaller();
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

    @Gated("plugin.installer.add")
    public void grantInstaller(String username) {
        requireInstaller();
        UserAccounts.Account account = accounts.byUsername(username)
                .orElseThrow(() -> new PluginRefusedException(List.of(
                        new Violation("not-found", "No user named '" + username + "'.", "Check the username."))));
        gate.run(
                Operation.of(new PluginOperations.GrantInstaller(username)),
                () -> audited("PLUGIN_INSTALLER_GRANT", username, Map.of(), () -> {
                    installers.grant(account.id(), actor().username());
                    return null;
                }));
    }

    @Gated("plugin.installer.remove")
    public void revokeInstaller(UUID userId) {
        requireInstaller();
        String username =
                accounts.byId(userId).map(UserAccounts.Account::username).orElse(userId.toString());
        gate.run(
                Operation.of(new PluginOperations.RevokeInstaller(userId)),
                () -> audited("PLUGIN_INSTALLER_REVOKE", username, Map.of(), () -> {
                    if (!installers.revoke(userId)) {
                        throw new PluginRefusedException(List.of(new Violation(
                                "last-installer",
                                "'" + username + "' is the only one who can install plugins.",
                                "Add another installer first.")));
                    }
                    return null;
                }));
    }

    // ---- trusted keys and the allowance (design.md §6) --------------------------------------------------

    /** Every trusted key, the allowance, and the installed plugins each key signed. */
    public record TrustedKeys(
            List<PluginTrust.TrustedKey> keys, boolean allowUnverified, Map<String, List<String>> signedPlugins) {}

    public TrustedKeys keys() {
        requireInstaller();
        Map<String, List<String>> signed = host.list().stream()
                .filter(p -> p.status() != PluginInstallStatus.UNINSTALLED && p.signerFingerprint() != null)
                .collect(Collectors.groupingBy(
                        PluginSummary::signerFingerprint,
                        TreeMap::new,
                        Collectors.mapping(PluginSummary::id, Collectors.toList())));
        return new TrustedKeys(trust.keys(), trust.allowUnverified(), signed);
    }

    /**
     * Trusts a publisher key given as exactly one of a pending upload, whose own signer is taken
     * from the stored jar, or a PEM. The audit row opens first, so a refusal is recorded too.
     */
    @Gated("plugin.trust-key.add")
    public PluginTrust.TrustedKey addKey(String name, String uploadSha256, String pem, Runnable stepUp) {
        Map<String, Object> params = new HashMap<>();
        // The PEM itself is not recorded: it can be 8 KB of whatever the client sent.
        params.put("source", uploadSha256 != null ? UPLOAD : "pem");
        if (uploadSha256 != null) {
            params.put(UPLOAD, uploadSha256);
        }
        checkedFirst("PLUGIN_KEY_ADD", name.strip(), params, stepUp, () -> {
            if ((uploadSha256 == null) == (pem == null)) {
                throw new IllegalArgumentException("Give exactly one of upload and pem.");
            }
        });
        return gate.run(
                Operation.of(new PluginOperations.AddTrustKey(name.strip(), uploadSha256, pem)),
                () -> audited("PLUGIN_KEY_ADD", name.strip(), params, () -> {
                    requireInstaller();
                    Signer signer = uploadSha256 != null ? host.uploadSigner(uploadSha256) : PublisherKeys.parse(pem);
                    return trust.add(name.strip(), signer, actor().username());
                }));
    }

    @Gated("plugin.trust-key.remove")
    public void removeKey(String fingerprint, Runnable stepUp) {
        checkedFirst("PLUGIN_KEY_REMOVE", fingerprint, Map.of(), stepUp, () -> {});
        gate.run(
                Operation.of(new PluginOperations.RemoveTrustKey(fingerprint)),
                () -> audited("PLUGIN_KEY_REMOVE", fingerprint, Map.of(), () -> {
                    requireInstaller();
                    if (!trust.remove(fingerprint)) {
                        throw notFound("No trusted key " + fingerprint + ".");
                    }
                    return null;
                }));
    }

    @Gated("plugin.trust-policy.set")
    public void setAllowUnverified(boolean allow, Runnable stepUp) {
        Map<String, Object> params = Map.of("allowUnverified", allow);
        checkedFirst("PLUGIN_TRUST_POLICY", "allow-unverified", params, stepUp, () -> {});
        gate.run(
                Operation.of(new PluginOperations.SetTrustPolicy(allow)),
                () -> audited("PLUGIN_TRUST_POLICY", "allow-unverified", params, () -> {
                    requireInstaller();
                    trust.setAllowUnverified(allow, actor().username());
                    return null;
                }));
    }

    // ---- checks and audit ------------------------------------------------------------------------------

    private void requireInstaller() {
        installBlocker().ifPresent(e -> {
            throw e;
        });
    }

    /**
     * The checks bound to the request, before it reaches the gate: the installer tier, the step-up and
     * {@code more}. A refusal is audited, as an action that begins its own audit row would have.
     */
    private void checkedFirst(String action, String target, Map<String, ?> params, Runnable stepUp, Runnable more) {
        try {
            requireInstaller();
            stepUp.run();
            more.run();
        } catch (RuntimeException e) {
            AuditEvent event = audit.begin(actors.resolve(), action, "plugin", target, null, null, params, false);
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    /**
     * The installer tier and a sign-in or step-up in the last five minutes. The web layer demands it
     * before it calls an action, and the actions do not, so a held action can be replayed without the
     * requester's session.
     */
    public void requireStepUp(HttpServletRequest request) {
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

    private static PluginRefusedException notFound(String message) {
        return new PluginRefusedException(List.of(new Violation("not-found", message, "")));
    }

    /**
     * An activation of a jar that is not signed by a trusted key records that and the signer, since
     * the row is begun before the host reports the plan (design.md §6). Unreadable jars add nothing:
     * the host refuses them, and that refusal is what gets audited.
     */
    private Map<String, Object> withTrust(Map<String, Object> params, String sha256) {
        Map<String, Object> written = new HashMap<>(params);
        try {
            var decision = sha256 == null ? null : host.plan(sha256).trust();
            if (decision != null && decision.status() != TrustDecision.Status.TRUSTED) {
                written.put("trust", "unverified");
                if (decision.fingerprint() != null) {
                    written.put("fingerprint", decision.fingerprint());
                }
            }
        } catch (RuntimeException _) {
            // Nothing to record; the activation itself refuses and is audited as failed.
        }
        return written;
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
