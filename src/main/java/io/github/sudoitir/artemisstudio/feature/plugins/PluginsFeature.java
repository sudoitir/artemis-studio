package io.github.sudoitir.artemisstudio.feature.plugins;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureModule;

/**
 * Loads this module while {@code artemis-studio.features.plugins.enabled} is not {@code false}.
 * Disabled, installed plugins still start; only their administration is gone.
 */
@FeatureModule("plugins")
public class PluginsFeature {}
