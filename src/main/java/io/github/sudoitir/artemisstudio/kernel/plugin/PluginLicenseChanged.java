package io.github.sudoitir.artemisstudio.kernel.plugin;

/**
 * A plugin's license file was uploaded, replaced or removed. Published in every plugin's context on
 * every Studio replica, the one the change was made on included, so a plugin re-reads its file with
 * {@link PluginLicense#file()}. Every plugin receives it: a plugin that handles it compares
 * {@code pluginId} with its own and ignores the rest.
 *
 * @param pluginId the plugin whose license changed
 */
@PluginApi
public record PluginLicenseChanged(String pluginId) {}
