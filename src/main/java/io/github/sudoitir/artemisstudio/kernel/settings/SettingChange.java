package io.github.sudoitir.artemisstudio.kernel.settings;

import java.util.Objects;

/**
 * One change in a change set: a new value for a setting, or its reset to the packaged default.
 *
 * @param key the setting
 * @param value the new value, or {@code null} to reset the setting
 */
public record SettingChange(String key, String value) {

    public SettingChange {
        Objects.requireNonNull(key, "key");
    }

    public static SettingChange set(String key, String value) {
        return new SettingChange(key, Objects.requireNonNull(value, "value"));
    }

    public static SettingChange reset(String key) {
        return new SettingChange(key, null);
    }

    public boolean isReset() {
        return value == null;
    }
}
