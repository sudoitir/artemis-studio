package io.github.sudoitir.artemisstudio.feature.plugins.web;

import io.github.sudoitir.artemisstudio.feature.plugins.PluginAdministration;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.GrantInstallerRequest;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginBudgetView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginChangesetView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginContributionsView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginDiffView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginInfoView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginInstallerView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginMcpToolView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginPermissionView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginPlanView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginProblemReasonView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginPurgePlanView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginTableEstimateView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginUpdateView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginUploadView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginVendorView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginViolationView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.PluginsView;
import io.github.sudoitir.artemisstudio.feature.plugins.web.PluginAdminViews.StudioRestartView;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditQueryService;
import io.github.sudoitir.artemisstudio.kernel.audit.web.AuditViews.AuditEventView;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.ActivationPlan;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginHost;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginRefusedException;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginSummary;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PurgePlan;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.StudioRestart;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.ChangesetInfo;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Violation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → Plugins (ADR-0099, ADR-0103). Everyone who administers users sees the inventory; only
 * an installer, in a browser session, changes it — and every change but an upload needs a
 * sign-in or step-up in the last five minutes. Activation answers {@code 202}: it runs on,
 * and its progress and outcome are the plugin's {@code status} and {@code progress}.
 */
@RestController
@RequestMapping("/api/v1/admin/plugins")
@PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
@RequiredArgsConstructor
public class PluginAdminController {

    /** design.md §7: a plugin jar is at most 50 MB. */
    static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;

    private final PluginAdministration administration;
    private final PluginHost host;
    private final StudioRestart restart;
    private final AuditQueryService audit;

    @GetMapping
    public PluginsView list() {
        var blocker = administration.installBlocker();
        var budget = host.connectionBudget();
        List<PluginSummary> plugins = host.list();
        List<String> unreleased = host.unreleased();
        return new PluginsView(
                blocker.isEmpty(),
                blocker.map(e -> new PluginProblemReasonView(e.slug(), e.getMessage()))
                        .orElse(null),
                administration.uploadEnabled(),
                host.safeMode(),
                host.safeModeReason().orElse(null),
                new PluginBudgetView(budget.maxConnections(), budget.inUse(), budget.limit(), budget.perPlugin()),
                new StudioRestartView(
                        restart.supervised(),
                        !unreleased.isEmpty()
                                || plugins.stream()
                                        .anyMatch(p -> p.status() == PluginInstallStatus.NEEDS_RESTART || p.stuck()),
                        restart.restarting(),
                        restart.manualRestartAllowedAt().orElse(null),
                        StudioRestart.MANUAL_COMMAND,
                        unreleased),
                plugins.stream().map(this::view).toList());
    }

    @GetMapping("/{id}")
    public PluginView get(@PathVariable String id) {
        return host.status(id).map(this::view).orElseThrow(() -> notFound("No installed plugin '" + id + "'."));
    }

    /** The jar is the raw request body — never multipart, so nothing is parsed before this runs. */
    @PutMapping(path = "/upload", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PluginUploadView upload(HttpServletRequest request) throws IOException {
        administration.installBlocker().ifPresent(e -> {
            throw e;
        });
        if (request.getContentLengthLong() > MAX_UPLOAD_BYTES) {
            throw new PluginUploadTooLargeException();
        }
        Path jar = Files.createTempFile(
                "plugin-upload-",
                ".jar",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            long bytes;
            try (InputStream in = request.getInputStream();
                    OutputStream out = Files.newOutputStream(jar)) {
                bytes = copyBounded(in, out);
            }
            if (bytes > MAX_UPLOAD_BYTES) {
                throw new PluginUploadTooLargeException();
            }
            var inspection = administration.upload(jar, bytes);
            return new PluginUploadView(
                    inspection.sha256(),
                    plan(inspection.plan()),
                    inspection.warnings().stream()
                            .map(PluginAdminController::violation)
                            .toList());
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    @GetMapping("/uploads/{sha256}")
    public PluginPlanView upload(@PathVariable String sha256) {
        return plan(administration.planUpload(sha256));
    }

    @DeleteMapping("/uploads/{sha256}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@PathVariable String sha256) {
        administration.discardUpload(sha256);
    }

    @PostMapping("/uploads/{sha256}/activate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PluginPlanView activate(HttpServletRequest request, @PathVariable String sha256) {
        return plan(administration.activate(request, sha256));
    }

    @PostMapping("/{id}/enable")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PluginPlanView enable(HttpServletRequest request, @PathVariable String id) {
        return plan(administration.enable(request, id));
    }

    @PostMapping("/{id}/rollback")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PluginPlanView rollback(HttpServletRequest request, @PathVariable String id) {
        return plan(administration.rollback(request, id));
    }

    /** Refused while other active plugins require it, unless {@code cascade} disables them too. */
    @PostMapping("/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(
            HttpServletRequest request,
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean cascade) {
        administration.disable(request, id, cascade);
    }

    /** Stops and removes the plugin; its data stays until a purge. */
    @PostMapping("/{id}/uninstall")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uninstall(
            HttpServletRequest request,
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean cascade) {
        administration.uninstall(request, id, cascade);
    }

    /** Deletes an uninstalled plugin's data for good; {@code dryRun=true} only estimates it. */
    @PostMapping("/{id}/purge")
    public PluginPurgePlanView purge(
            HttpServletRequest request, @PathVariable String id, @RequestParam(defaultValue = "false") boolean dryRun) {
        PurgePlan plan = administration.purge(request, id, dryRun);
        return new PluginPurgePlanView(
                plan.schema(),
                plan.tables().stream()
                        .map(t -> new PluginTableEstimateView(t.name(), t.estimatedRows(), t.bytes()))
                        .toList(),
                plan.grantsCount(),
                plan.settingsCount(),
                plan.artifactsCount());
    }

    /** Everything recorded about the plugin, newest first: who uploaded, activated, changed or removed it. */
    @GetMapping("/{id}/history")
    public List<AuditEventView> history(@PathVariable String id) {
        return audit.forTarget("plugin", id, 100);
    }

    /** Restarts Studio for its plugins, when a supervisor will start it again (ADR-0104). */
    @PostMapping("/restart")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void restart(HttpServletRequest request) {
        administration.restartStudio(request);
    }

    @PostMapping("/check-updates")
    public List<PluginUpdateView> checkUpdates() {
        return administration.checkForUpdates().stream()
                .map(a -> new PluginUpdateView(
                        a.id(), a.currentVersion(), a.availableVersion(), a.changeNotes(), a.error()))
                .toList();
    }

    /** Fetches the offered update and inspects it like an upload; activating it is a separate step. */
    @PostMapping("/{id}/download-update")
    @ResponseStatus(HttpStatus.CREATED)
    public PluginUploadView downloadUpdate(@PathVariable String id) throws IOException {
        var inspection = administration.downloadUpdate(id);
        return new PluginUploadView(
                inspection.sha256(),
                plan(inspection.plan()),
                inspection.warnings().stream()
                        .map(PluginAdminController::violation)
                        .toList());
    }

    @GetMapping("/installers")
    public List<PluginInstallerView> installers() {
        return administration.installers().stream()
                .map(i -> new PluginInstallerView(i.userId(), i.username(), i.grantedAt(), i.grantedBy()))
                .toList();
    }

    @PostMapping("/installers")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grantInstaller(HttpServletRequest request, @Valid @RequestBody GrantInstallerRequest body) {
        administration.grantInstaller(request, body.username());
    }

    @DeleteMapping("/installers/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInstaller(HttpServletRequest request, @PathVariable UUID userId) {
        administration.revokeInstaller(request, userId);
    }

    // ---- mapping ------------------------------------------------------------------------------

    private PluginView view(PluginSummary s) {
        boolean active = s.status() == PluginInstallStatus.ACTIVE;
        return new PluginView(
                s.id(),
                s.version(),
                s.status().dbValue(),
                s.failure(),
                s.progress(),
                s.stepStartedAt(),
                s.installedAt(),
                s.activatedAt(),
                s.installedBy(),
                s.sha256(),
                s.rollbackAvailable(),
                s.stuck(),
                active
                        ? "/plugin-ui/%s/%s/icon.svg"
                                .formatted(s.id(), s.sha256().substring(0, 8))
                        : null,
                active ? host.dependantsOf(s.id()) : List.of(),
                info(s.descriptor(), s.id(), s.vendor()));
    }

    private static PluginInfoView info(PluginDescriptor d, String id, String vendor) {
        if (d == null) {
            return new PluginInfoView(
                    id,
                    id,
                    "Its stored descriptor could not be read.",
                    new PluginVendorView(vendor, null, null),
                    null,
                    null,
                    "",
                    null,
                    false,
                    null,
                    List.of(),
                    new PluginContributionsView(false, List.of(), List.of(), List.of(), List.of()));
        }
        return new PluginInfoView(
                d.name(),
                d.title() == null ? d.name() : d.title(),
                d.description(),
                new PluginVendorView(
                        d.vendor().name(), d.vendor().url(), d.vendor().email()),
                d.license(),
                d.changeNotes(),
                d.studio().since(),
                d.studio().until(),
                d.activation() == PluginDescriptor.Activation.RESTART,
                d.updateUrl(),
                d.requires(),
                new PluginContributionsView(
                        d.ui(),
                        d.permissions().stream()
                                .map(p -> new PluginPermissionView(p.action(), p.description()))
                                .toList(),
                        d.settingKeys(),
                        d.streamTopics(),
                        d.mcpTools().stream()
                                .map(t -> new PluginMcpToolView(t.name(), t.posture(), t.description()))
                                .toList()));
    }

    private static PluginPlanView plan(ActivationPlan p) {
        var d = p.diff();
        return new PluginPlanView(
                p.pluginId(),
                p.fromVersion(),
                p.toVersion(),
                p.activationClass().name(),
                p.pendingChangesets().stream()
                        .map(c -> new PluginChangesetView(c.id(), c.author(), c.reversible()))
                        .toList(),
                p.updateSql(),
                p.pendingChangesets().stream().allMatch(ChangesetInfo::reversible),
                new PluginDiffView(
                        sorted(d.permissionsAdded()),
                        sorted(d.permissionsRemoved()),
                        sorted(d.settingKeysAdded()),
                        sorted(d.settingKeysRemoved()),
                        sorted(d.streamTopicsAdded()),
                        sorted(d.streamTopicsRemoved()),
                        sorted(d.mcpToolsAdded()),
                        sorted(d.mcpToolsRemoved())),
                p.rolesLosingPermission(),
                p.compatible(),
                p.missingRequires(),
                p.restart().name(),
                info(p.descriptor(), p.pluginId(), p.descriptor().vendor().name()));
    }

    static PluginViolationView violation(Violation v) {
        return new PluginViolationView(
                v.code(),
                v.message(),
                v.authorFix() == null ? "" : v.authorFix(),
                v.severity().name());
    }

    private static List<String> sorted(Collection<String> values) {
        return values.stream().sorted().toList();
    }

    private static PluginRefusedException notFound(String message) {
        return new PluginRefusedException(List.of(new Violation("not-found", message, "")));
    }

    /** Copies at most one byte past the limit, so an oversize body is detected without reading all of it. */
    private static long copyBounded(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        while (total <= MAX_UPLOAD_BYTES
                && (n = in.read(buffer, 0, (int) Math.min(buffer.length, MAX_UPLOAD_BYTES + 1 - total))) > 0) {
            out.write(buffer, 0, n);
            total += n;
        }
        return total;
    }
}
