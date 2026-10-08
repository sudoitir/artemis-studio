package io.github.sudoitir.artemisstudio.kernel.gate;

import java.util.Objects;
import java.util.UUID;

/**
 * Lets work the gate has already decided on pass it again: an approved operation's replay, or the
 * items of an allowed or approved parent (ADR-0180). Only the gate creates tickets, and it honours
 * only tickets it issued itself, by identity; a ticket built anywhere else is ignored. Not part of
 * the plugin API.
 *
 * @param heldId the approved held operation being replayed, or {@code null} for a covering parent
 * @param type the operation type it was issued for
 * @param paramsHash the hex hash a replay must match, or {@code null} for a covering parent
 */
public record GateTicket(UUID heldId, String type, String paramsHash) {

    public GateTicket {
        Objects.requireNonNull(type, "type");
    }

    /** Whether this ticket replays one held operation, rather than covering a parent's items. */
    public boolean replay() {
        return heldId != null;
    }
}
