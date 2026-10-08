package io.github.sudoitir.artemisstudio.kernel.gate;

/**
 * The gate's ticket for the current work (ADR-0180). While {@link #COVERED} is bound to a ticket the
 * gate issued, the gate runs nested operations without asking again; the worker hand-off carries it
 * to a bulk run's threads. Bound by the gate only. Not part of the plugin API.
 */
public final class GateScope {

    public static final ScopedValue<GateTicket> COVERED = ScopedValue.newInstance();

    private GateScope() {}
}
