package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/** The settings API. Values are strings; the service parses and validates per key. */
public final class SettingsViews {

    private SettingsViews() {}

    /**
     * One tunable, described well enough that a client can render an editor for it
     * without knowing the key. {@code group}, {@code label}, {@code hint} and
     * {@code kind} come from the {@code SettingsService} registry, so adding a
     * setting server-side adds it to the Settings screen with no frontend change
     * (ADR-0047).
     *
     * @param value the effective value (override if present, else the default)
     * @param overridden whether a {@code studio_setting} row is in effect
     * @param defaultValue the packaged default, for "reset" affordances
     * @param group the section to render this under
     * @param label the human name of the setting
     * @param hint one line on what it does and what changing it costs
     * @param kind {@code DURATION}, {@code INT} or {@code CRON} — how to validate and edit it
     */
    public record SettingValue(
            @Schema(requiredMode = REQUIRED) String value,
            @Schema(requiredMode = REQUIRED) boolean overridden,
            @Schema(requiredMode = REQUIRED) String defaultValue,
            @Schema(requiredMode = REQUIRED) String group,
            @Schema(requiredMode = REQUIRED) String label,
            @Schema(requiredMode = REQUIRED) String hint,
            @Schema(requiredMode = REQUIRED) String kind) {}

    public record SettingsResponse(
            @Schema(requiredMode = REQUIRED) Map<String, SettingValue> settings) {}

    public record UpdateSettingRequest(@NotBlank String value) {}
}
