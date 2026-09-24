package io.github.sudoitir.artemisstudio.kernel.plugin.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The installation manifest (ADR-0070). Describes what is offered; it grants nothing. */
public final class ManifestViews {

    /**
     * @param version the {@code FeatureRegistry} manifest version — a client polls this to detect
     *     a stale cached copy once a plugin activates, updates or is removed (task 6.9)
     * @param safeMode whether this boot started with no plugin running (design.md §2)
     */
    public record ManifestView(
            @Schema(requiredMode = REQUIRED) int contract,
            @Schema(requiredMode = REQUIRED) String version,
            @Schema(requiredMode = REQUIRED) boolean safeMode,
            @Schema(requiredMode = REQUIRED) List<ManifestFeatureView> features,
            @Schema(requiredMode = REQUIRED) List<ManifestPermissionView> permissionCatalogue,
            @Schema(requiredMode = REQUIRED) List<ManifestIdentityProviderView> identityProviders) {}

    /**
     * @param enabledProperty the startup property that enables or disables the module, for a client to name
     *     where a disabled feature's view would be
     * @param origin {@code BUILTIN} or {@code PLUGIN} (task 6.9)
     * @param version the installed version, for a {@code PLUGIN} entry only
     * @param vendor the installing vendor's name, for a {@code PLUGIN} entry only
     * @param status the {@code plugin_install} status, for a {@code PLUGIN} entry only
     * @param ui present only when the plugin has a UI bundle and is active
     */
    public record ManifestFeatureView(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) String title,
            @Schema(requiredMode = REQUIRED) String kind,
            @Schema(requiredMode = REQUIRED) boolean enabled,
            @Schema(requiredMode = REQUIRED) String enabledProperty,
            @Schema(requiredMode = REQUIRED) List<String> permissions,
            @Schema(requiredMode = REQUIRED) List<String> topics,
            @Schema(requiredMode = REQUIRED) String origin,
            @Schema(nullable = true) String version,
            @Schema(nullable = true) String vendor,
            @Schema(nullable = true) String status,
            @Schema(nullable = true) ManifestPluginUiView ui) {}

    public record ManifestPluginUiView(
            @Schema(requiredMode = REQUIRED) String entry) {}

    public record ManifestPermissionView(
            @Schema(requiredMode = REQUIRED) String action,
            @Schema(requiredMode = REQUIRED) String label,
            @Schema(requiredMode = REQUIRED) String featureId) {}

    /**
     * @param kind {@code CREDENTIAL} or {@code REDIRECT}
     * @param startPath where a redirect provider's sign-in begins; {@code null} for a credential provider
     */
    public record ManifestIdentityProviderView(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) String kind,
            @Schema(requiredMode = REQUIRED) String label,

            @Schema(requiredMode = REQUIRED, nullable = true)
            String startPath) {}

    private ManifestViews() {}
}
