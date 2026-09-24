package io.github.sudoitir.artemisstudio.kernel.settings;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The read-only slice of {@link SettingsService} exported into the plugin API context (design.md,
 * task 6.1): a plugin may read an effective setting value, never write one — writes stay behind
 * {@code SettingsService.put}'s own {@code @PreAuthorize}, which a plugin's curated context never
 * resolves.
 */
@Component
@PluginApi
@RequiredArgsConstructor
public class SettingsReader {

    private final SettingsService settings;

    /** The effective raw value: the stored override, else the packaged default. */
    public String value(String key) {
        return settings.value(key);
    }

    public Duration duration(String key) {
        return settings.duration(key);
    }

    public int intValue(String key) {
        return settings.intValue(key);
    }
}
