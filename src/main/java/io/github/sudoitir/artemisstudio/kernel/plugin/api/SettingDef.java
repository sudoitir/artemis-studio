package io.github.sudoitir.artemisstudio.kernel.plugin.api;

import java.util.function.Supplier;

/**
 * One operator-tunable runtime setting (ADR-0047). {@code defaultValue} renders the
 * packaged default rather than capturing it, so the fallback shown is always the
 * current configuration.
 *
 * @param apply pushes a changed value into a consumer that caches it; {@code null}
 *     when the consumer reads the setting on each use
 */
public record SettingDef(
        String key, String group, String label, String hint, Kind kind, Supplier<String> defaultValue, Runnable apply) {

    /**
     * How a value is parsed, validated and rendered. Three kinds on purpose: a fourth
     * would mean a new input on the settings screen, which is a design decision.
     */
    public enum Kind {
        /** A Spring-style ({@code 5s}, {@code 72h}) or ISO-8601 duration. Must be positive. */
        DURATION,
        /** A whole number, at least 1. */
        INT,
        /** A six-field Spring cron expression. Rejected if it would fire more than once a minute. */
        CRON
    }
}
