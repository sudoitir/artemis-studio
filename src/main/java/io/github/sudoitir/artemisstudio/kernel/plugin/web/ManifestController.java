package io.github.sudoitir.artemisstudio.kernel.plugin.web;

import io.github.sudoitir.artemisstudio.kernel.plugin.api.Contract;
import io.github.sudoitir.artemisstudio.kernel.plugin.api.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.api.FeatureRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.api.PermissionDef;
import io.github.sudoitir.artemisstudio.kernel.plugin.api.TopicDef;
import io.github.sudoitir.artemisstudio.kernel.plugin.web.ManifestViews.ManifestFeatureView;
import io.github.sudoitir.artemisstudio.kernel.plugin.web.ManifestViews.ManifestIdentityProviderView;
import io.github.sudoitir.artemisstudio.kernel.plugin.web.ManifestViews.ManifestPermissionView;
import io.github.sudoitir.artemisstudio.kernel.plugin.web.ManifestViews.ManifestView;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/manifest} — every built-in module with whether it is enabled,
 * the permission catalogue of the enabled ones, and the configured identity
 * providers. Authenticated like the rest of {@code /api/**}; it grants nothing.
 */
@RestController
public class ManifestController {

    private final FeatureRegistry registry;
    private final Supplier<List<ManifestIdentityProviderView>> identityProviders;

    public ManifestController(FeatureRegistry registry, ObjectProvider<IdentityProviderListing> identityProviders) {
        this.registry = registry;
        this.identityProviders =
                () -> identityProviders.getIfAvailable(() -> List::of).providers();
    }

    @GetMapping("/api/v1/manifest")
    public ManifestView manifest() {
        List<ManifestFeatureView> features = registry.all().stream()
                .map(d -> new ManifestFeatureView(
                        d.id(),
                        d.title(),
                        d.kind().name(),
                        registry.isEnabled(d.id()),
                        d.permissions().stream().map(PermissionDef::action).toList(),
                        d.streamTopics().stream().map(TopicDef::name).toList()))
                .toList();
        List<ManifestPermissionView> catalogue = registry.enabled().stream()
                .flatMap(d -> d.permissions().stream().map(p -> permission(d, p)))
                .toList();
        return new ManifestView(Contract.VERSION, features, catalogue, identityProviders.get());
    }

    private static ManifestPermissionView permission(FeatureDescriptor d, PermissionDef p) {
        return new ManifestPermissionView(p.action(), p.label(), d.id());
    }

    /** Supplies the manifest's identity providers; implemented by the security kernel. */
    @FunctionalInterface
    public interface IdentityProviderListing {
        List<ManifestIdentityProviderView> providers();
    }
}
