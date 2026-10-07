package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.Objects;

/**
 * One line of what an operation will change.
 *
 * @param label what changes, such as "Queue" or "Max delivery attempts"
 * @param from the value now, or {@code null} when there is none
 * @param to the value after, or {@code null} when it is removed
 */
@PluginApi
public record DisplayRow(String label, String from, String to) {

    public DisplayRow {
        Objects.requireNonNull(label, "label");
    }

    /** A row that names a target rather than a change. */
    public static DisplayRow of(String label, String value) {
        return new DisplayRow(label, null, value);
    }
}
