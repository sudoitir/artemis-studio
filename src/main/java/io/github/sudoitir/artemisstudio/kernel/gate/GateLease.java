package io.github.sudoitir.artemisstudio.kernel.gate;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A hold on the gate's ticket by work that continues an operation after the gate's action returned, such as a bulk
 * run's workers (ADR-0180). The worker hand-off takes one when it captures the operator inside a covered operation,
 * the first run of that operator enters it, and leaving that run releases it. A covering ticket stays honoured while
 * the gate's action runs or any lease on it is held, and is revoked when the last of them ends; a lease that was
 * released no longer carries its ticket. Created by the gate only. Not part of the plugin API.
 */
public final class GateLease {

    private static final int FRESH = 0;
    private static final int ENTERED = 1;
    private static final int RELEASED = 2;

    private final GateTicket ticket;
    private final Runnable onRelease;
    private final AtomicInteger state = new AtomicInteger(FRESH);

    public GateLease(GateTicket ticket, Runnable onRelease) {
        this.ticket = Objects.requireNonNull(ticket, "ticket");
        this.onRelease = Objects.requireNonNull(onRelease, "onRelease");
    }

    public GateTicket ticket() {
        return ticket;
    }

    /** Marks the start of the run that owns this lease; true only for the first. */
    public boolean enter() {
        return state.compareAndSet(FRESH, ENTERED);
    }

    /** Whether the lease was released, so its ticket no longer covers anything run under it. */
    public boolean released() {
        return state.get() == RELEASED;
    }

    /** Ends the lease; only the first call releases the hold. */
    public void release() {
        if (state.getAndSet(RELEASED) != RELEASED) {
            onRelease.run();
        }
    }
}
