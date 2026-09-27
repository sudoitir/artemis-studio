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
        List<McpTool> mcpTools,
        List<Metric> metrics,
        List<AlertRule> alertRules) {

    public PluginDescriptor {
        requires = requires == null ? List.of() : List.copyOf(requires);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        settingKeys = settingKeys == null ? List.of() : List.copyOf(settingKeys);
        streamTopics = streamTopics == null ? List.of() : List.copyOf(streamTopics);
        mcpTools = mcpTools == null ? List.of() : List.copyOf(mcpTools);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        alertRules = alertRules == null ? List.of() : List.copyOf(alertRules);
    }

    public record Vendor(String name, String url, String email) {}

    public record Studio(String since, String until) {}

    public record Permission(String action, String description) {}

    public record McpTool(String name, String posture, String description) {}

    /**
     * A metric the plugin publishes through a {@code PluginMetricSource} (ADR-0113).
     *
     * @param name {@code <id>:<name>}, the identifier the source returns
     * @param unit {@code count}, {@code per_second}, {@code ms} or {@code ratio}
     * @param subject what the values are keyed by, for example "job"
     * @param permission the plugin permission that reads the metric's series
     */
    public record Metric(String name, String description, String unit, String subject, String permission) {}

    /** A default threshold rule on one of the plugin's metrics, seeded once per cluster (ADR-0113). */
    public record AlertRule(
            String key,
            String name,
            String metric,
            String comparator,
            double threshold,
            int forSeconds,
            String severity) {}

    public enum Activation {
        AUTO,
        RESTART
    }
}
