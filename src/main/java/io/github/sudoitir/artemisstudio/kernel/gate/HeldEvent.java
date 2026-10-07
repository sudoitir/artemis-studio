package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.time.Instant;
import java.util.UUID;

/**
 * One entry of a held operation's timeline, read in commit order through {@link
 * HeldOperations#eventsAfter}.
 *
 * @param actorId who caused it, or {@code null} when Studio did (expiry, a sweep)
 * @param detail a sentence about it, or {@code null}
 */
@PluginApi
public record HeldEvent(
        EventCursor cursor, UUID heldId, Kind kind, UUID actorId, String actorUsername, String detail, Instant at) {

    public enum Kind {
        REQUESTED,
        APPROVED,
        REJECTED,
        VOTE_REFUSED,
        CANCELLED,
        EXPIRED,
        EXECUTING,
        SUCCEEDED,
        FAILED,
        REFUSED,
        OUTCOME_UNKNOWN
    }
}
