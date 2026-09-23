package io.github.sudoitir.artemisstudio.kernel.plugin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code artemis-studio.plugins.*}. {@code studioVersionOverride} exists only so tests can pin
 * the running version without a real {@code build-info.properties} (task 5.2). {@code safeMode}
 * forces the crash-loop guard's safe mode regardless of boot history (design.md §2, task 6.8).
 * {@code startTimeoutSeconds} bounds how long a plugin gets to start at boot before it is marked
 * {@code needs_restart}; it is overridable so a test can trip the timeout deterministically.
 */
@ConfigurationProperties(prefix = "artemis-studio.plugins")
public record PluginProperties(String studioVersionOverride, boolean safeMode, Integer startTimeoutSeconds) {

    public PluginProperties {
        studioVersionOverride = studioVersionOverride == null ? "" : studioVersionOverride;
        startTimeoutSeconds = startTimeoutSeconds == null ? 60 : startTimeoutSeconds;
    }
}
