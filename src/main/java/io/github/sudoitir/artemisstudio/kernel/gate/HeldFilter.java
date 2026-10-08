package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.Set;
import java.util.UUID;

/**
 * Which held operations {@link HeldOperations#list} returns, newest first.
 *
 * @param states the states to include; empty for all
 * @param requesterId only this requester's, or {@code null}
 * @param clusterId only this cluster's, or {@code null}
 * @param limit at most this many, from 1 to 500
 */
@PluginApi
public record HeldFilter(Set<HeldState> states, UUID requesterId, UUID clusterId, int limit) {

    public HeldFilter {
        states = Set.copyOf(states);
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be from 1 to 500");
        }
    }

    public static HeldFilter open(int limit) {
        return new HeldFilter(Set.of(HeldState.HELD, HeldState.APPROVED, HeldState.EXECUTING), null, null, limit);
    }
}
