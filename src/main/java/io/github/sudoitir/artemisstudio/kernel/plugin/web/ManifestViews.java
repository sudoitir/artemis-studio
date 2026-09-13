package io.github.sudoitir.artemisstudio.kernel.plugin.web;

import java.util.List;

/** The installation manifest (ADR-0070). Describes what is offered; it grants nothing. */
public final class ManifestViews {

    public record ManifestView(
            int contract,
            List<ManifestFeatureView> features,
            List<ManifestPermissionView> permissionCatalogue,
            List<ManifestIdentityProviderView> identityProviders) {}

    public record ManifestFeatureView(
            String id, String title, String kind, boolean enabled, List<String> permissions, List<String> topics) {}

    public record ManifestPermissionView(String action, String label, String featureId) {}

    /**
     * @param kind {@code CREDENTIAL} or {@code REDIRECT}
     * @param startPath where a redirect provider's sign-in begins; {@code null} for a credential provider
     */
    public record ManifestIdentityProviderView(String id, String kind, String label, String startPath) {}

    private ManifestViews() {}
}
