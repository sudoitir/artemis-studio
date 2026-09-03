package io.github.sudoitir.artemisstudio.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/** The settings API. Values are strings; the service parses and validates per key. */
public final class SettingsViews {

    private SettingsViews() {}

    /**
     * @param value the effective value (override if present, else the default)
     * @param overridden whether a {@code studio_setting} row is in effect
     * @param defaultValue the compile-time default, for "reset" affordances
     */
    public record SettingValue(String value, boolean overridden, String defaultValue) {}

    public record SettingsResponse(Map<String, SettingValue> settings) {}

    public record UpdateSettingRequest(@NotBlank String value) {}
}
