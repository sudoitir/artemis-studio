package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/** The approval provider denied the operation. Mapped to HTTP 403 {@code operation-denied} with its reason. */
@PluginApi
public class OperationDeniedException extends RuntimeException {

    public OperationDeniedException(String reason) {
        super(reason);
    }
}
