package io.github.sudoitir.artemisstudio.kernel.settings;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One operator-tunable runtime setting (ADR-0047), contributed by the module that
 * reads it. {@code defaultValue} renders the packaged default rather than capturing
 * it, so the fallback shown is always the current configuration.
 *
 * @param apply pushes a changed value into a consumer that caches it; {@code null}
 *     when the consumer reads the setting from {@link SettingsService} on each use
 * @param min the smallest accepted value, in the kind's own syntax; {@code null} for the kind's
 *     floor (a positive duration, an int of at least 1)
 * @param max the largest accepted value; {@code null} for none. For a {@link Kind#DURATION}
 *     the literal {@link #FOREVER} both lifts the ceiling and allows {@code forever} as a value
 */
@PluginApi
public record SettingDef(
        String key,
        String group,
        String label,
        String hint,
        Kind kind,
        Supplier<String> defaultValue,
        Consumer<SettingsService> apply,
        String min,
        String max) {

    /** The duration value, and the {@link #max}, meaning "no limit" (ADR-0134). */
    public static final String FOREVER = "forever";

    /** A setting bounded only by its kind's floor. */
    public SettingDef(
            String key,
            String group,
            String label,
            String hint,
            Kind kind,
            Supplier<String> defaultValue,
            Consumer<SettingsService> apply) {
        this(key, group, label, hint, kind, defaultValue, apply, null, null);
    }

    /**
     * How a value is parsed, validated and rendered. Each kind is an input on the settings
     * screen, so a new one is a design decision, not a config one (ADR-0136).
     */
    public enum Kind {
        /** A Spring-style ({@code 5s}, {@code 72h}) or ISO-8601 duration. Must be positive, or {@link #FOREVER} where allowed. */
        DURATION,
        /** A whole number, at least {@code min} (1 when unset). */
        INT,
        /** A six-field Spring cron expression. Rejected if it would fire more than once a minute. */
        CRON,
        /** {@code true} or {@code false}, rendered as a switch. */
        BOOLEAN
    }
}
