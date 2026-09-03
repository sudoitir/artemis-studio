package io.github.sudoitir.artemisstudio.scheduler;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-node page cursor for the tier-C full sweep (ADR-0015). Tier C fetches one
 * {@code listQueues} page per tick and walks the whole set over several ticks,
 * then wraps. The sweep's start time is held constant across its pages so the
 * stale-row reap can delete everything the completed sweep did not refresh.
 *
 * <p>In memory: a restart just starts the next sweep from page 1.
 */
@Component
public class SweepCursor {

    private record State(int nextPage, Instant sweepStart) {}

    private final Map<UUID, State> byNode = new ConcurrentHashMap<>();

    /** The page to fetch next for this node. Page 1 begins a fresh sweep. */
    public int nextPage(UUID nodeId) {
        return byNode.computeIfAbsent(nodeId, k -> new State(1, Instant.now())).nextPage();
    }

    /** The instant the current sweep began — constant until it wraps. */
    public Instant sweepStart(UUID nodeId) {
        State s = byNode.get(nodeId);
        return s == null ? Instant.now() : s.sweepStart();
    }

    /**
     * Advance the cursor after a page was fetched. When {@code wasLastPage}, wrap
     * to page 1 and stamp a new sweep start; otherwise step to the next page,
     * keeping the same start.
     */
    public void advance(UUID nodeId, boolean wasLastPage) {
        byNode.compute(nodeId, (k, s) -> {
            State current = s != null ? s : new State(1, Instant.now());
            return wasLastPage ? new State(1, Instant.now()) : new State(current.nextPage() + 1, current.sweepStart());
        });
    }

    public void forget(UUID nodeId) {
        byNode.remove(nodeId);
    }
}
