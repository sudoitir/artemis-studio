package io.github.sudoitir.artemisstudio.kernel.plugin;

/**
 * Published by {@link FeatureRegistry} whenever the active plugin set changes — a plugin
 * activated or deactivated (design.md, task 6.4). {@code manifestVersion} is the same string
 * {@link FeatureRegistry#manifestVersion()} returns right after the change, so a listener that
 * missed intermediate events can still tell whether its own cached copy is stale.
 */
public record PluginsChanged(String manifestVersion) {}
