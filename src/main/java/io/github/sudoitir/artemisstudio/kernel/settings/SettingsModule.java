package io.github.sudoitir.artemisstudio.kernel.settings;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;

/** Runtime operational settings and deploy-time properties. Module descriptor (ADR-0070). */
public final class SettingsModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("settings")
            .title("Settings")
            .kind(FeatureDescriptor.Kind.KERNEL)
            .required(true)
            .permission(new PermissionDef(SettingsPermissions.SETTINGS_READ, "View operational settings"))
            .permission(new PermissionDef(
                    SettingsPermissions.SETTINGS_WRITE, "Change operational settings and rotate credentials"))
            .build();

    private SettingsModule() {}
}
