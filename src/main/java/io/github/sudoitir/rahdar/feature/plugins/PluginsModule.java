package io.github.sudoitir.rahdar.feature.plugins;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;

/** Plugin administration. Module descriptor (ADR-0070). */
public final class PluginsModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("plugins")
            .title("Plugins")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .apiPrefix("/api/v1/admin/plugins")
            .build();

    private PluginsModule() {}
}
