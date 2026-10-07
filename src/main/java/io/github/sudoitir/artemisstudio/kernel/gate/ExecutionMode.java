package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/** Who runs an approved operation (ADR-0180). */
@PluginApi
public enum ExecutionMode {
    /** Studio runs it in the background as the requester as soon as it is approved. */
    ON_APPROVAL,
    /** The requester runs it by submitting it again before the run deadline; for secret results. */
    BY_REQUESTER
}
