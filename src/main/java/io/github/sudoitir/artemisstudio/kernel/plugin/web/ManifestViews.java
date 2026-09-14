package io.github.sudoitir.artemisstudio.kernel.plugin.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The installation manifest (ADR-0070). Describes what is offered; it grants nothing. */
public final class ManifestViews {

    public record ManifestView(
            @Schema(requiredMode = REQUIRED) int contract,
            @Schema(requiredMode = REQUIRED) List<ManifestFeatureView> features,
            @Schema(requiredMode = REQUIRED) List<ManifestPermissionView> permissionCatalogue,
            @Schema(requiredMode = REQUIRED) List<ManifestIdentityProviderView> identityProviders) {}

    /**
     * @param enabledProperty the startup property that enables or disables the module, for a client to name
     *     where a disabled feature's view would be
     */
    public record ManifestFeatureView(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) String title,
            @Schema(requiredMode = REQUIRED) String kind,
            @Schema(requiredMode = REQUIRED) boolean enabled,
            @Schema(requiredMode = REQUIRED) String enabledProperty,
            @Schema(requiredMode = REQUIRED) List<String> permissions,
            @Schema(requiredMode = REQUIRED) List<String> topics) {}

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
