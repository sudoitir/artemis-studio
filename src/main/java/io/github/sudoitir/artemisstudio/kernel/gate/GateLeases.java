package io.github.sudoitir.artemisstudio.kernel.gate;

import java.util.Optional;

/** Takes {@link GateLease}s on the gate's covering tickets; the gate's engine implements it. Not part of the plugin API. */
public interface GateLeases {

    /** A lease on {@code ticket} when it is a covering ticket the gate issued that is still honoured; empty otherwise. */
    Optional<GateLease> retain(GateTicket ticket);
}
