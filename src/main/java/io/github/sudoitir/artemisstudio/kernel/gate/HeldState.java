package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/**
 * Where a held operation is (ADR-0180). {@code HELD} moves to {@code APPROVED}, {@code REJECTED},
 * {@code CANCELLED} or {@code EXPIRED}; {@code APPROVED} to {@code EXECUTING}, {@code CANCELLED} or
 * {@code EXPIRED}; {@code EXECUTING} to {@code SUCCEEDED}, {@code FAILED}, {@code REFUSED} or {@code
 * OUTCOME_UNKNOWN}. Nothing else moves.
 */
@PluginApi
public enum HeldState {
    HELD,
    APPROVED,
    EXECUTING,
    REJECTED,
    CANCELLED,
    EXPIRED,
    SUCCEEDED,
    FAILED,
    /** A check before running refused it: integrity, version, requester, state key or the provider. */
    REFUSED,
    /** The replica running it disappeared; it is never run again. */
    OUTCOME_UNKNOWN;

    /** Whether the operation is still waiting or running. */
    public boolean open() {
        return this == HELD || this == APPROVED || this == EXECUTING;
    }
}
