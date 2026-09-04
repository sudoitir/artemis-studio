package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
    public record SettingValue(
            @Schema(requiredMode = REQUIRED) String value,
            @Schema(requiredMode = REQUIRED) boolean overridden,
            @Schema(requiredMode = REQUIRED) String defaultValue) {}

    public record SettingsResponse(
            @Schema(requiredMode = REQUIRED) Map<String, SettingValue> settings) {}

    public record UpdateSettingRequest(@NotBlank String value) {}
}
