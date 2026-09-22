package io.github.sudoitir.artemisstudio.feature.transfer;

import java.util.Set;

/** Where a transfer run is (transfer design D5). */
public enum TransferState {
    PREVIEWED,
    RUNNING,
    /** The target is near full: the run waits for room and continues by itself. */
    WAITING_FOR_CAPACITY,
    /** Putting the messages held in staging back on the source queue. */
    RETURNING,
    /** Every selected message was transferred. */
    SUCCEEDED,
    /** Finished, with some selected messages not transferred; the run says how many and why. */
    PARTIAL,
    /** Stopped by the operator, a withdrawn permission, or a target that stayed full. Resumable. */
    STOPPED,
    /** Studio stopped while it ran. Resumable. */
    INTERRUPTED,
    /** A node failed mid-run. Resumable; a move's held messages are safe in staging. */
    FAILED,
    /** A move's held messages were put back on the source queue. */
    RETURNED;

    /** Executing in this Studio: at most one such run per source queue. */
    public static final Set<TransferState> ACTIVE = Set.of(RUNNING, WAITING_FOR_CAPACITY, RETURNING);

    /** Ended without finishing: may be resumed, and a move returned to its source. */
    public static final Set<TransferState> RESUMABLE = Set.of(STOPPED, INTERRUPTED, FAILED);

    public boolean active() {
        return ACTIVE.contains(this);
    }

    public boolean resumable() {
        return RESUMABLE.contains(this);
    }
}
