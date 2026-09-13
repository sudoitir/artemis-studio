package io.github.sudoitir.artemisstudio.kernel.settings;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.McpToolDef;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;
import java.util.List;

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
            .mcpTool(new McpToolDef(
                    "studio_setting",
                    McpToolDef.Posture.MUTATE,
                    "Read or change an operational setting: scrape cadence, rate limit, retention, bulk cap.",
                    List.of(
                            McpToolDef.Param.values("op", List.of("get", "set"), "Default get."),
                            McpToolDef.Param.note("key", "Omit on get for every setting."))))
            .build();

    private SettingsModule() {}
}
