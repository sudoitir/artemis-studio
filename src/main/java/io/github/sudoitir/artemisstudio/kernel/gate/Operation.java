package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.Objects;

/**
 * One request to run a gated operation: its typed parameters. The gate finds the {@link
 * GatedOperation} whose {@link GatedOperation#paramsType()} is the parameters' class.
 */
@PluginApi
public record Operation(Record params) {

    public Operation {
        Objects.requireNonNull(params, "params");
    }

    public static Operation of(Record params) {
        return new Operation(params);
    }
}
