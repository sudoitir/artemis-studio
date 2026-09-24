package io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor;

import java.util.List;

/**
 * Mirrors {@code META-INF/artemis-studio/plugin.json} (design.md §1), validated against
 * {@code META-INF/artemis-studio/plugin.schema.json}. One field per JSON property; unknown
 * properties are rejected by {@link PluginDescriptorParser}, not silently dropped.
 */
public record PluginDescriptor(
        int schemaVersion,
        String id,
        String name,
        String version,
        Vendor vendor,
        String description,
        String license,
        String changeNotes,
        String basePackage,
        String configuration,
        int contract,
        Studio studio,
        List<String> requires,
        boolean ui,
        Activation activation,
        String updateUrl,
        String title,
        List<Permission> permissions,
        List<String> settingKeys,
        List<String> streamTopics,
        List<McpTool> mcpTools) {

    public PluginDescriptor {
        requires = requires == null ? List.of() : List.copyOf(requires);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        settingKeys = settingKeys == null ? List.of() : List.copyOf(settingKeys);
        streamTopics = streamTopics == null ? List.of() : List.copyOf(streamTopics);
        mcpTools = mcpTools == null ? List.of() : List.copyOf(mcpTools);
    }

    public record Vendor(String name, String url, String email) {}

    public record Studio(String since, String until) {}

    public record Permission(String action, String description) {}

    public record McpTool(String name, String posture, String description) {}

    public enum Activation {
        AUTO,
        RESTART
    }
}
