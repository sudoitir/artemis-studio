package io.github.sudoitir.artemisstudio.feature.plugins.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The plugin administration API's shapes (ADR-0099, ADR-0103). */
public final class PluginAdminViews {

    private PluginAdminViews() {}

    /**
     * @param cannotInstall why this caller cannot install, update or remove plugins; absent when they can
     * @param safeModeReason present only in safe mode, when no plugin was started
     */
    public record PluginsView(
            @Schema(requiredMode = REQUIRED) boolean canInstall,
            @Schema(nullable = true) PluginProblemReasonView cannotInstall,
            @Schema(requiredMode = REQUIRED) boolean uploadEnabled,
            @Schema(requiredMode = REQUIRED) boolean safeMode,
            @Schema(nullable = true) String safeModeReason,
            @Schema(requiredMode = REQUIRED) PluginBudgetView budget,
            @Schema(requiredMode = REQUIRED) StudioRestartView restart,
            @Schema(requiredMode = REQUIRED) List<PluginView> plugins) {}

    /**
     * Whether and how Studio can be restarted for its plugins (ADR-0104).
     *
     * @param supervised Studio can restart itself: something starts it again after it exits
     * @param needed a plugin waits for a restart, or a stopped one did not free its memory
     * @param restarting a restart is under way; reconnect once {@code /actuator/health} answers
     * @param allowedAt a manual restart is refused until then, so soon after the last start
     * @param command what to run instead, when Studio cannot restart itself
     * @param unreleased plugin versions that were stopped but are still in memory
     */
    public record StudioRestartView(
            @Schema(requiredMode = REQUIRED) boolean supervised,
            @Schema(requiredMode = REQUIRED) boolean needed,
            @Schema(requiredMode = REQUIRED) boolean restarting,
            @Schema(nullable = true) Instant allowedAt,
            @Schema(requiredMode = REQUIRED) String command,
            @Schema(requiredMode = REQUIRED) List<String> unreleased) {}

    /** A refusal as a stable slug the UI can branch on, and a sentence it can show. */
    public record PluginProblemReasonView(
            @Schema(requiredMode = REQUIRED) String code,
            @Schema(requiredMode = REQUIRED) String message) {}

    /**
     * Database connections: {@code inUse} is 10 for Studio plus {@code perPlugin} for each active
     * plugin; activation is refused past {@code limit}, 80% of Postgres' {@code max_connections}.
     */
    public record PluginBudgetView(
            @Schema(requiredMode = REQUIRED) int maxConnections,
            @Schema(requiredMode = REQUIRED) int inUse,
            @Schema(requiredMode = REQUIRED) int limit,
            @Schema(requiredMode = REQUIRED) int perPlugin) {}

    /**
     * @param status {@code activating}, {@code active}, {@code disabled}, {@code failed},
     *     {@code incompatible}, {@code needs_restart} or {@code uninstalled}
     * @param progress the activation step in flight, while {@code activating}
     * @param stuck the running version did not close cleanly; a restart frees what it holds
     * @param iconUrl present while active; the icon may still be absent, so fall back on error
     * @param dependants active plugins that require this one
     */
    public record PluginView(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) String version,
            @Schema(requiredMode = REQUIRED) String status,
            @Schema(nullable = true) String failure,
            @Schema(nullable = true) String progress,
            @Schema(nullable = true) Instant stepStartedAt,
            @Schema(requiredMode = REQUIRED) Instant installedAt,
            @Schema(nullable = true) Instant activatedAt,
            @Schema(nullable = true) String installedBy,
            @Schema(requiredMode = REQUIRED) String sha256,
            @Schema(requiredMode = REQUIRED) boolean rollbackAvailable,
            @Schema(requiredMode = REQUIRED) boolean stuck,
            @Schema(nullable = true) String iconUrl,
            @Schema(requiredMode = REQUIRED) List<String> dependants,
            @Schema(requiredMode = REQUIRED) PluginInfoView info) {}

    /** What a plugin says about itself, from its descriptor. */
    public record PluginInfoView(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String title,
            @Schema(nullable = true) String description,
            @Schema(requiredMode = REQUIRED) PluginVendorView vendor,
            @Schema(nullable = true) String license,
            @Schema(nullable = true) String changeNotes,
            @Schema(requiredMode = REQUIRED) String since,
            @Schema(nullable = true) String until,
            @Schema(requiredMode = REQUIRED) boolean restartToActivate,
            @Schema(nullable = true) String updateUrl,
            @Schema(requiredMode = REQUIRED) List<String> requires,
            @Schema(requiredMode = REQUIRED) PluginContributionsView contributions) {}

    public record PluginVendorView(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(nullable = true) String url,
            @Schema(nullable = true) String email) {}

    public record PluginContributionsView(
            @Schema(requiredMode = REQUIRED) boolean ui,
            @Schema(requiredMode = REQUIRED) List<PluginPermissionView> permissions,
            @Schema(requiredMode = REQUIRED) List<String> settingKeys,
            @Schema(requiredMode = REQUIRED) List<String> streamTopics,
            @Schema(requiredMode = REQUIRED) List<PluginMcpToolView> mcpTools) {}

    public record PluginPermissionView(
            @Schema(requiredMode = REQUIRED) String action,
            @Schema(nullable = true) String description) {}

    /** @param posture {@code read} or {@code write} */
    public record PluginMcpToolView(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String posture,
            @Schema(nullable = true) String description) {}

    /** A validated, stored upload, and what activating it would do. Nothing is installed yet. */
    public record PluginUploadView(
            @Schema(requiredMode = REQUIRED) String sha256,
            @Schema(requiredMode = REQUIRED) PluginPlanView plan,
            @Schema(requiredMode = REQUIRED) List<PluginViolationView> warnings) {}

    /**
     * What an activation will do, shown before it is confirmed (design.md §5, §6).
     *
     * @param fromVersion absent for a fresh install
     * @param activationClass {@code INSTANT}: no downtime; {@code BRIEF_MAINTENANCE}: this plugin
     *     answers 503 for seconds while its database changes; {@code RESTART}: Studio must restart
     * @param reversible every pending database change carries a rollback
     * @param missingRequires required plugins or features that are not active; activation is refused
     */
    public record PluginPlanView(
            @Schema(requiredMode = REQUIRED) String pluginId,
            @Schema(nullable = true) String fromVersion,
            @Schema(requiredMode = REQUIRED) String toVersion,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"INSTANT", "BRIEF_MAINTENANCE", "RESTART"})
            String activationClass,

            @Schema(requiredMode = REQUIRED) List<PluginChangesetView> pendingChangesets,
            @Schema(requiredMode = REQUIRED) String updateSql,
            @Schema(requiredMode = REQUIRED) boolean reversible,
            @Schema(requiredMode = REQUIRED) PluginDiffView diff,
            @Schema(requiredMode = REQUIRED) Map<String, Integer> rolesLosingPermission,
            @Schema(requiredMode = REQUIRED) boolean compatible,
            @Schema(requiredMode = REQUIRED) List<String> missingRequires,

            @Schema(
                    requiredMode = REQUIRED,
                    description =
                            "NONE; AUTOMATIC: Studio restarts itself once confirmed; MANUAL: restart it yourself.",
                    allowableValues = {"NONE", "AUTOMATIC", "MANUAL"})
            String restart,

            @Schema(requiredMode = REQUIRED) PluginInfoView info) {}

    public record PluginChangesetView(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) String author,
            @Schema(requiredMode = REQUIRED) boolean reversible) {}

    /** Compared with the installed version; every list is empty for a fresh install. */
    public record PluginDiffView(
            @Schema(requiredMode = REQUIRED) List<String> permissionsAdded,
            @Schema(requiredMode = REQUIRED) List<String> permissionsRemoved,
            @Schema(requiredMode = REQUIRED) List<String> settingKeysAdded,
            @Schema(requiredMode = REQUIRED) List<String> settingKeysRemoved,
            @Schema(requiredMode = REQUIRED) List<String> streamTopicsAdded,
            @Schema(requiredMode = REQUIRED) List<String> streamTopicsRemoved,
            @Schema(requiredMode = REQUIRED) List<String> mcpToolsAdded,
            @Schema(requiredMode = REQUIRED) List<String> mcpToolsRemoved) {}

    /** @param fix what the plugin's author must change; empty when there is nothing to change */
    public record PluginViolationView(
            @Schema(requiredMode = REQUIRED) String code,
            @Schema(requiredMode = REQUIRED) String message,
            @Schema(requiredMode = REQUIRED) String fix,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"ERROR", "WARNING"})
            String severity) {}

    /** A purge's reach, estimated from table statistics without counting rows. */
    public record PluginPurgePlanView(
            @Schema(requiredMode = REQUIRED) String schema,
            @Schema(requiredMode = REQUIRED) List<PluginTableEstimateView> tables,
            @Schema(requiredMode = REQUIRED) long grants,
            @Schema(requiredMode = REQUIRED) long settings,
            @Schema(requiredMode = REQUIRED) long artifacts) {}

    public record PluginTableEstimateView(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) long estimatedRows,
            @Schema(requiredMode = REQUIRED) long bytes) {}

    /** @param availableVersion absent when nothing newer is offered; {@code error} says when asking failed */
    public record PluginUpdateView(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) String currentVersion,
            @Schema(nullable = true) String availableVersion,
            @Schema(nullable = true) String changeNotes,
            @Schema(nullable = true) String error) {}

    public record PluginInstallerView(
            @Schema(requiredMode = REQUIRED) UUID userId,
            @Schema(requiredMode = REQUIRED) String username,
            @Schema(requiredMode = REQUIRED) Instant grantedAt,
            @Schema(nullable = true) String grantedBy) {}

    public record GrantInstallerRequest(
            @jakarta.validation.constraints.NotBlank String username) {}
}
