package io.github.sudoitir.rahdar.feature.plugins;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureModule;

/**
 * Loads this module while {@code rahdar.features.plugins.enabled} is not {@code false}.
 * Disabled, installed plugins still start; only their administration is gone.
 */
@FeatureModule("plugins")
public class PluginsFeature {}
