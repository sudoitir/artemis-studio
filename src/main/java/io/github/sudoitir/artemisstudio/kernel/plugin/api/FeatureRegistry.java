package io.github.sudoitir.artemisstudio.kernel.plugin.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.core.env.Environment;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The installation's modules and which of them are enabled, validated once at
 * startup (feature-modules spec). Startup fails — naming what is wrong — when a
 * module was built against another contract version, when two modules claim the
 * same permission, setting, topic or tool, when a required module is disabled, or
 * when an enabled module requires a disabled one.
 */
@Component
public class FeatureRegistry {

    private final Map<String, FeatureDescriptor> byId = new LinkedHashMap<>();
    private final Map<String, Boolean> enabled = new HashMap<>();
    private final List<Map.Entry<PathPattern, FeatureDescriptor>> disabledPrefixes = new ArrayList<>();

    public FeatureRegistry(InstalledFeatures installed, Environment environment) {
        for (FeatureDescriptor d : installed.descriptors()) {
            if (d.contract() != Contract.VERSION) {
                throw new IllegalStateException("Feature '" + d.id() + "' was built against contract version "
                        + d.contract() + " but this installation is version " + Contract.VERSION);
            }
            if (byId.putIfAbsent(d.id(), d) != null) {
                throw new IllegalStateException("Feature id '" + d.id() + "' is declared twice");
            }
        }
        rejectDuplicates(
                "permission",
                d -> d.permissions().stream().map(PermissionDef::action).toList());
        rejectDuplicates(
                "setting", d -> d.settings().stream().map(SettingDef::key).toList());
        rejectDuplicates(
                "stream topic",
                d -> d.streamTopics().stream().map(TopicDef::name).toList());
        rejectDuplicates(
                "MCP tool", d -> d.mcpTools().stream().map(McpToolDef::name).toList());

        for (FeatureDescriptor d : byId.values()) {
            String property = Contract.enabledProperty(d.id());
            boolean on = environment.getProperty(property, Boolean.class, true);
            if (!on && d.required()) {
                throw new IllegalStateException(
                        "Module '" + d.id() + "' is required and cannot be disabled; remove " + property + "=false");
            }
            enabled.put(d.id(), on);
        }
        for (FeatureDescriptor d : byId.values()) {
            for (String dependency : d.requires()) {
                if (!byId.containsKey(dependency)) {
                    throw new IllegalStateException(
                            "Feature '" + d.id() + "' requires '" + dependency + "', which is not installed");
                }
                if (enabled.get(d.id()) && !enabled.get(dependency)) {
                    throw new IllegalStateException("Feature '" + d.id() + "' is enabled but requires '" + dependency
                            + "', which is disabled by " + Contract.enabledProperty(dependency) + "=false");
                }
            }
            if (!enabled.get(d.id())) {
                for (String prefix : d.apiPrefixes()) {
                    disabledPrefixes.add(Map.entry(PathPatternParser.defaultInstance.parse(prefix + "/**"), d));
                }
            }
        }
    }

    private void rejectDuplicates(String what, Function<FeatureDescriptor, List<String>> names) {
        Map<String, String> owner = new HashMap<>();
        for (FeatureDescriptor d : byId.values()) {
            for (String name : names.apply(d)) {
                String previous = owner.putIfAbsent(name, d.id());
                if (previous != null) {
                    throw new IllegalStateException("The " + what + " '" + name + "' is declared by both '" + previous
                            + "' and '" + d.id() + "'");
                }
            }
        }
    }

    /** Every installed module, in declaration order. */
    public List<FeatureDescriptor> all() {
        return List.copyOf(byId.values());
    }

    public List<FeatureDescriptor> enabled() {
        return byId.values().stream().filter(d -> enabled.get(d.id())).toList();
    }

    public boolean isEnabled(String featureId) {
        return enabled.getOrDefault(featureId, false);
    }

    /** The disabled feature that would have served {@code path}, if any. */
    public Optional<FeatureDescriptor> disabledOwnerOf(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return disabledPrefixes.stream()
                .filter(e -> e.getKey().matches(container))
                .map(Map.Entry::getValue)
                .findFirst();
    }
}
