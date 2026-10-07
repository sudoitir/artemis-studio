package io.github.sudoitir.artemisstudio.kernel.plugin;

/**
 * Published by {@code PluginHost} when an install leaves the statuses where it is meant to be
 * running ({@link PluginInstallStatus#desiredActive()}): an administrator disabled or uninstalled
 * it. A purge needs the plugin uninstalled first, so it has already published this. It is published inside the
 * transaction that changes the row, and a plain {@code @EventListener} runs synchronously in that
 * transaction, so a listener can cancel what depended on the plugin atomically with its removal; a
 * listener that throws rolls the change back. An upgrade, a rollback and a failure do not publish it.
 *
 * @param actor who did it, as the lifecycle log records it
 */
public record PluginStatusChanged(String pluginId, PluginInstallStatus from, PluginInstallStatus to, String actor) {}
