package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.Objects;

/**
 * What an operation would do, estimated when it is requested and again before it runs.
 *
 * @param count how many {@code unit}s it affects
 * @param unit what is counted, such as "messages" or "queues"
 * @param stateKey a precondition Studio enforces as equality before running: a hash of the current
 *     values, of a bulk run's items, or the target's identity
 * @param detail a sentence for the approver, or {@code null}
 */
@PluginApi
public record Effect(long count, String unit, String stateKey, String detail) {

    public Effect {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(stateKey, "stateKey");
    }
}
