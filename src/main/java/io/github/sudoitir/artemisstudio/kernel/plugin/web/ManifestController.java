package io.github.sudoitir.artemisstudio.kernel.plugin.web;

import io.github.sudoitir.artemisstudio.kernel.plugin.Contract;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.IdentityProviderListing;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.TopicDef;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorException;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginHost;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.web.ManifestViews.ManifestFeatureView;
import io.github.sudoitir.artemisstudio.kernel.plugin.web.ManifestViews.ManifestIdentityProviderView;
import io.github.sudoitir.artemisstudio.kernel.plugin.web.ManifestViews.ManifestPermissionView;
import io.github.sudoitir.artemisstudio.kernel.plugin.web.ManifestViews.ManifestPluginUiView;
import io.github.sudoitir.artemisstudio.kernel.plugin.web.ManifestViews.ManifestView;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/manifest} — every built-in module with whether it is enabled, every
 * installed plugin with its version/vendor/status (task 6.9), the permission catalogue of the
 * enabled built-ins and active plugins, and the configured identity providers. Authenticated like
 * the rest of {@code /api/**}; it grants nothing.
 */
@RestController
@Slf4j
public class ManifestController {

    private static final int SHA_PREFIX_LENGTH = 8;

    private final FeatureRegistry registry;
    private final PluginInstallRepository installs;
    private final PluginDescriptorParser descriptorParser;
    private final PluginHost host;
    private final Supplier<List<ManifestIdentityProviderView>> identityProviders;

    public ManifestController(
            FeatureRegistry registry,
            PluginInstallRepository installs,
            PluginDescriptorParser descriptorParser,
            PluginHost host,
            ObjectProvider<IdentityProviderListing> identityProviders) {
        this.registry = registry;
        this.installs = installs;
        this.descriptorParser = descriptorParser;
        this.host = host;
        this.identityProviders = () -> identityProviders.getIfAvailable(() -> List::of).providers().stream()
                .map(p -> new ManifestIdentityProviderView(p.id(), p.kind(), p.label(), p.startPath()))
                .toList();
    }

    @GetMapping("/api/v1/manifest")
    public ManifestView manifest() {
        List<ManifestFeatureView> builtins = registry.all().stream()
                .map(d -> new ManifestFeatureView(
                        d.id(),
                        d.title(),
                        d.kind().name(),
                        registry.isEnabled(d.id()),
                        Contract.enabledProperty(d.id()),
                        d.permissions().stream().map(PermissionDef::action).toList(),
                        d.streamTopics().stream().map(TopicDef::name).toList(),
                        "BUILTIN",
                        null,
                        null,
                        null,
                        null))
                .toList();
        List<ManifestFeatureView> plugins =
                installs.findAll().stream().map(this::pluginView).toList();
        List<ManifestFeatureView> features = java.util.stream.Stream.concat(builtins.stream(), plugins.stream())
                .toList();

        List<ManifestPermissionView> catalogue = java.util.stream.Stream.concat(
                        registry.enabled().stream()
                                .flatMap(d -> d.permissions().stream().map(p -> permission(d, p))),
                        registry.plugins().stream()
                                .flatMap(d -> d.permissions().stream().map(p -> permission(d, p))))
                .toList();

        return new ManifestView(
                Contract.VERSION,
                registry.manifestVersion(),
                host.safeMode(),
                features,
                catalogue,
                identityProviders.get());
    }

    private ManifestFeatureView pluginView(PluginInstallEntity entity) {
        PluginDescriptor descriptor = tryParse(entity);
        boolean active = entity.status() == PluginInstallStatus.ACTIVE;
        List<String> permissions = descriptor == null
                ? List.of()
                : descriptor.permissions().stream()
                        .map(PluginDescriptor.Permission::action)
                        .toList();
        List<String> topics = descriptor == null ? List.of() : descriptor.streamTopics();
        String title = descriptor == null ? entity.getId() : descriptor.title();
        ManifestPluginUiView ui = active && descriptor != null && descriptor.ui()
                ? new ManifestPluginUiView("/plugin-ui/%s/%s/remoteEntry.js"
                        .formatted(entity.getId(), entity.getSha256().substring(0, SHA_PREFIX_LENGTH)))
                : null;
        return new ManifestFeatureView(
                entity.getId(),
                title,
                FeatureDescriptor.Kind.PLUGIN.name(),
                active,
                "",
                permissions,
                topics,
                "PLUGIN",
                entity.getVersion(),
                entity.getVendor(),
                entity.status().dbValue(),
                ui);
    }

    private PluginDescriptor tryParse(PluginInstallEntity entity) {
        try {
            return descriptorParser.parse(entity.getDescriptor().getBytes(StandardCharsets.UTF_8));
        } catch (PluginDescriptorException e) {
            log.warn("Stored descriptor for plugin '{}' could not be parsed for the manifest", entity.getId(), e);
            return null;
        }
    }

    private static ManifestPermissionView permission(FeatureDescriptor d, PermissionDef p) {
        return new ManifestPermissionView(p.action(), p.label(), d.id());
    }
}
