package io.github.sudoitir.artemisstudio.kernel.plugin;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
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
 *
 * <p>{@link #addPlugin(PluginDescriptor)} and {@link #removePlugin(String)} (design.md, task 6.4)
 * add a second, independently mutable layer on top of that fixed startup validation: an active
 * plugin's namespaced permissions, settings, topics and MCP tools, checked for collisions against
 * the built-ins and every other currently active plugin the same way {@link #rejectDuplicates}
 * always has. A collision fails only that plugin's activation — the built-in registry above is
 * never touched. Every change publishes {@link PluginsChanged} and rolls {@link #manifestVersion()}
 * forward, so a client polling the manifest can tell its cached copy is stale.
 */
@Component
public class FeatureRegistry implements PluginBridge {

    private final Map<String, FeatureDescriptor> byId = new LinkedHashMap<>();
    private final Map<String, Boolean> enabled = new HashMap<>();
    private final List<Map.Entry<PathPattern, FeatureDescriptor>> prefixes = new ArrayList<>();
    private final ApplicationEventPublisher events;

    /** Copy-on-write: read without a lock, written under {@code this} (rare — one plugin at a time). */
    private volatile Map<String, FeatureDescriptor> plugins = Map.of();

    /**
     * Which {@link PluginHandle} currently owns each active plugin id, guarded by {@code this} the
     * same as {@link #plugins}. Lets {@link #detach} tell a superseded version's own detach apart
     * from the currently active one: the Instant activation class attaches a new version before the
     * old one detaches, so both briefly hold the same id, and the old one's detach must not remove
     * the new one's registration.
     */
    private final Map<String, PluginHandle> owners = new HashMap<>();

    /** Version of each active plugin, written with {@link #plugins}; what {@link #manifestVersion} hashes. */
    private volatile Map<String, String> pluginVersions = Map.of();

    private volatile String manifestVersion = manifestVersionOf(Map.of());

    public FeatureRegistry(InstalledFeatures installed, Environment environment, ApplicationEventPublisher events) {
        this.events = events;
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
        rejectDuplicates("setting", FeatureDescriptor::settingKeys);
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
            requireInstalledAndEnabled(d);
            for (String prefix : d.apiPrefixes()) {
                prefixes.add(Map.entry(PathPatternParser.defaultInstance.parse(prefix + "/**"), d));
            }
        }
    }

    private void requireInstalledAndEnabled(FeatureDescriptor d) {
        for (String dependency : d.requires()) {
            if (!byId.containsKey(dependency)) {
                throw new IllegalStateException(
                        "Feature '" + d.id() + "' requires '" + dependency + "', which is not installed");
            }
            if (isEnabled(d.id()) && !isEnabled(dependency)) {
                throw new IllegalStateException("Feature '" + d.id() + "' is enabled but requires '" + dependency
                        + "', which is disabled by " + Contract.enabledProperty(dependency) + "=false");
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

    /**
     * Activates a plugin's namespaced contributions into the live registry (design.md, task 6.4):
     * a {@link FeatureDescriptor.Kind#PLUGIN} entry, checked for permission/setting/topic/tool
     * collisions against the built-ins and every other active plugin exactly the way the
     * constructor checks built-ins against each other. Called once a plugin's context has refreshed
     * and before traffic is switched to it; a collision throws, which fails that plugin's activation
     * alone and leaves every other plugin and every built-in untouched.
     *
     * <p>A second call under an id already active supersedes the first rather than colliding with
     * it: the Instant activation class (design.md §5) warms up and attaches a new version before
     * the old one's bridges detach, so both versions briefly hold the same id here. Collision
     * checks below compare the new descriptor against every <em>other</em> active plugin, never
     * against its own immediately-prior version.
     */
    public synchronized void addPlugin(PluginDescriptor descriptor) {
        FeatureDescriptor asFeature = toFeatureDescriptor(descriptor);
        if (byId.containsKey(asFeature.id())) {
            throw new IllegalStateException("Plugin id '" + asFeature.id() + "' collides with an installed module");
        }
        Map<String, FeatureDescriptor> merged = new LinkedHashMap<>(byId);
        plugins.forEach((id, fd) -> {
            if (!id.equals(asFeature.id())) {
                merged.put(id, fd);
            }
        });
        merged.put(asFeature.id(), asFeature);
        rejectDuplicatesAmong(
                merged.values(),
                "permission",
                d -> d.permissions().stream().map(PermissionDef::action).toList());
        rejectDuplicatesAmong(merged.values(), "setting", FeatureDescriptor::settingKeys);
        rejectDuplicatesAmong(
                merged.values(),
                "stream topic",
                d -> d.streamTopics().stream().map(TopicDef::name).toList());
        rejectDuplicatesAmong(
                merged.values(),
                "MCP tool",
                d -> d.mcpTools().stream().map(McpToolDef::name).toList());

        Map<String, FeatureDescriptor> next = new LinkedHashMap<>(plugins);
        next.put(asFeature.id(), asFeature);
        plugins = Map.copyOf(next);
        Map<String, String> nextVersions = new HashMap<>(pluginVersions);
        nextVersions.put(asFeature.id(), descriptor.version());
        pluginVersions = Map.copyOf(nextVersions);
        rollManifestVersion();
    }

    /** Deactivates a plugin's contributions. A plugin not currently active is a no-op. */
    public synchronized void removePlugin(String pluginId) {
        if (!plugins.containsKey(pluginId)) {
            return;
        }
        Map<String, FeatureDescriptor> next = new LinkedHashMap<>(plugins);
        next.remove(pluginId);
        plugins = Map.copyOf(next);
        Map<String, String> nextVersions = new HashMap<>(pluginVersions);
        nextVersions.remove(pluginId);
        pluginVersions = Map.copyOf(nextVersions);
        rollManifestVersion();
    }

    /**
     * Changes whenever the active plugin set changes — a client polls this to detect a stale manifest.
     * A hash of the active {@code id@version} set, so replicas that run the same plugins agree on it.
     */
    public String manifestVersion() {
        return manifestVersion;
    }

    private void rollManifestVersion() {
        manifestVersion = manifestVersionOf(pluginVersions);
        events.publishEvent(new PluginsChanged(manifestVersion));
    }

    private static String manifestVersionOf(Map<String, String> versions) {
        String set = versions.entrySet().stream()
                .map(e -> e.getKey() + "@" + e.getValue())
                .sorted()
                .collect(Collectors.joining("\n"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(set.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static FeatureDescriptor toFeatureDescriptor(PluginDescriptor d) {
        return FeatureDescriptor.builder()
                .id(d.id())
                .contract(d.contract())
                .title(d.title())
                .kind(FeatureDescriptor.Kind.PLUGIN)
                .required(false)
                .requires(d.requires())
                .permissions(d.permissions().stream()
                        .map(p -> new PermissionDef(p.action(), p.description(), p.isGlobalOnly()))
                        .toList())
                .settingKeys(d.settingKeys())
                .streamTopics(d.streamTopics().stream().map(TopicDef::signal).toList())
                .mcpTools(d.mcpTools().stream()
                        .map(PluginDescriptor.McpTool::toCatalogueEntry)
                        .toList())
                .build();
    }

    private static void rejectDuplicatesAmong(
            Iterable<FeatureDescriptor> descriptors, String what, Function<FeatureDescriptor, List<String>> names) {
        Map<String, String> owner = new HashMap<>();
        for (FeatureDescriptor d : descriptors) {
            for (String name : names.apply(d)) {
                String previous = owner.putIfAbsent(name, d.id());
                if (previous != null) {
                    throw new IllegalStateException("The " + what + " '" + name + "' is declared by both '" + previous
                            + "' and '" + d.id() + "'");
                }
            }
        }
    }

    /** The module whose descriptor declares {@code settingKey}, enabled or not. */
    public Optional<FeatureDescriptor> ownerOfSetting(String settingKey) {
        return byId.values().stream()
                .filter(d -> d.settingKeys().contains(settingKey))
                .findFirst();
    }

    /** Every installed module, in declaration order. */
    public List<FeatureDescriptor> all() {
        return List.copyOf(byId.values());
    }

    /** Every currently active plugin's namespaced {@link FeatureDescriptor}: a plugin's entry exists
     * here only between {@link #addPlugin} and {@link #removePlugin}. */
    public List<FeatureDescriptor> plugins() {
        return List.copyOf(plugins.values());
    }

    /**
     * The permission catalogue: the enabled modules' permissions, then every active plugin's. The
     * one source for every reader (role editor, API-key picker, manifest), so they cannot drift.
     */
    public List<CatalogueEntry> catalogue() {
        return java.util.stream.Stream.concat(enabled().stream(), plugins.values().stream())
                .flatMap(d -> d.permissions().stream().map(p -> CatalogueEntry.of(d, p)))
                .toList();
    }

    public List<FeatureDescriptor> enabled() {
        return byId.values().stream().filter(d -> enabled.get(d.id())).toList();
    }

    public boolean isEnabled(String featureId) {
        return enabled.getOrDefault(featureId, false);
    }

    @Override
    public synchronized void attach(PluginHandle handle) {
        addPlugin(handle.descriptor());
        owners.put(handle.id(), handle);
    }

    /**
     * A no-op when {@code handle} is not the currently active owner of its id: it was already
     * superseded by a newer version's {@link #attach}, and that newer registration must survive.
     */
    @Override
    public synchronized void detach(PluginHandle handle) {
        if (owners.get(handle.id()) != handle) {
            return;
        }
        owners.remove(handle.id());
        removePlugin(handle.id());
    }

    /**
     * The disabled feature that would have served {@code path}, if any. The most
     * specific declared prefix decides, so a path one feature owns inside another
     * feature's broader prefix is attributed to its real owner.
     */
    public Optional<FeatureDescriptor> disabledOwnerOf(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return prefixes.stream()
                .filter(e -> e.getKey().matches(container))
                .min(Map.Entry.comparingByKey(PathPattern.SPECIFICITY_COMPARATOR))
                .map(Map.Entry::getValue)
                .filter(d -> !enabled.get(d.id()));
    }
}
