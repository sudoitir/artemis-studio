package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The held operations of the calling provider, read-only but for {@link #expire}. Studio keeps them
 * (ADR-0180); a provider sees only those it held.
 */
@PluginApi
public interface HeldOperations {

    Optional<HeldOperationView> get(UUID id);

    List<HeldOperationView> list(HeldFilter filter);

    /** Up to {@code limit} events after {@code cursor}, in commit order, with no gaps. */
    List<HeldEvent> eventsAfter(EventCursor cursor, int limit);

    /**
     * Ends a request that is still held or approved, as expired, with the reason the requester sees.
     *
     * @return whether it was open and is now expired
     */
    boolean expire(UUID id, String reason);
}
