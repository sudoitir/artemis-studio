package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/**
 * An approval provider is installed but could not answer: not running here, slow, or failing.
 * Mapped to HTTP 503 {@code approval-unavailable}; the operation did not run.
 */
@PluginApi
public class ApprovalUnavailableException extends RuntimeException {

    public ApprovalUnavailableException(String message) {
        super(message);
    }

    public ApprovalUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
