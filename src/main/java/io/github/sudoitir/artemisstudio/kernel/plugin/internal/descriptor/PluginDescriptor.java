package io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor;

import io.github.sudoitir.artemisstudio.kernel.plugin.McpToolDef;
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
        Boolean requiresLicense,
        Activation activation,
        String updateUrl,
        String title,
        List<Permission> permissions,
        List<String> settingKeys,
        List<String> streamTopics,
        List<McpTool> mcpTools,
        List<Metric> metrics,
        List<AlertRule> alertRules,
        List<IdentityProvider> identityProviders) {

    public PluginDescriptor {
        requires = requires == null ? List.of() : List.copyOf(requires);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        settingKeys = settingKeys == null ? List.of() : List.copyOf(settingKeys);
        streamTopics = streamTopics == null ? List.of() : List.copyOf(streamTopics);
        mcpTools = mcpTools == null ? List.of() : List.copyOf(mcpTools);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        alertRules = alertRules == null ? List.of() : List.copyOf(alertRules);
        identityProviders = identityProviders == null ? List.of() : List.copyOf(identityProviders);
    }

    /** Whether the plugin needs a license file; a plugin that does not say needs none. */
    public boolean isRequiresLicense() {
        return Boolean.TRUE.equals(requiresLicense);
    }

    public record Vendor(String name, String url, String email) {}

    public record Studio(String since, String until) {}

    /** @param globalOnly checked without a cluster, so only a global grant makes it take effect */
    public record Permission(String action, String description, Boolean globalOnly) {

        public boolean isGlobalOnly() {
            return Boolean.TRUE.equals(globalOnly);
        }
    }

    /**
     * An assistant tool the plugin registers. Studio checks {@code permission} before the tool runs:
     * on the cluster named by its {@code clusterId} argument when {@code scope} is {@code cluster},
     * otherwise globally.
     *
     * @param posture {@code read} or {@code write}
     * @param scope {@code cluster} or {@code global}
     * @param permission one of the plugin's declared permission actions
     * @param params the detail the tool's schema leaves out, shown by {@code studio_help}
     */
    public record McpTool(
            String name, String posture, String scope, String permission, String description, List<McpParam> params) {

        public McpTool {
            params = params == null ? List.of() : List.copyOf(params);
        }

        /** This tool as the MCP catalogue lists it: {@code write} is the catalogue's {@code MUTATE}. */
        public McpToolDef toCatalogueEntry() {
            return new McpToolDef(
                    name,
                    "read".equals(posture) ? McpToolDef.Posture.READ : McpToolDef.Posture.MUTATE,
                    description,
                    params.stream()
                            .map(p -> new McpToolDef.Param(p.name(), p.values(), p.shape(), p.note()))
                            .toList(),
                    new McpToolDef.Access(permission, scope));
        }
    }

    /**
     * @param values the values a discriminator accepts
     * @param shape the JSON or YAML shape a body argument takes
     * @param note anything a model gets wrong without being told
     */
    public record McpParam(String name, List<String> values, String shape, String note) {}

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

    /**
     * A username-and-password sign-in the plugin offers through a {@code PluginCredentialProvider}
     * bean (ADR-0156). Declared here so the login screen and the group mappings list it without
     * calling plugin code, and so the install review can say the plugin will receive passwords.
     *
     * @param id {@code <plugin id>:<name>}, the provider id Studio keys accounts and mappings by
     * @param label what the login screen shows, 1 to 64 characters
     */
    public record IdentityProvider(String id, String label) {}

    public enum Activation {
        AUTO,
        RESTART
    }
}
