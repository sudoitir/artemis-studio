package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/**
 * The policy that would hold the operation needs a reason, and none was given. Mapped to HTTP 422
 * {@code approval-reason-required}; submit again with {@link GateContext#REASON_HEADER}.
 */
@PluginApi
public class ApprovalReasonRequiredException extends RuntimeException {

    public ApprovalReasonRequiredException(String message) {
        super(message);
    }
}
