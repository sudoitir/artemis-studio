package io.github.sudoitir.artemisstudio.kernel.plugin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code artemis-studio.plugins.*}. {@code studioVersionOverride} exists only so tests can pin
 * the running version without a real {@code build-info.properties} (task 5.2).
 */
@ConfigurationProperties(prefix = "artemis-studio.plugins")
public record PluginProperties(String studioVersionOverride) {

    public PluginProperties {
        studioVersionOverride = studioVersionOverride == null ? "" : studioVersionOverride;
    }
}
