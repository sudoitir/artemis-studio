package io.github.sudoitir.artemisstudio.feature.flow;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one node's lifetime counters into per-member rates between two sweeps (ADR-0081 D4).
 *
 * <p>Deltas are taken per member id and only then aggregated, because a group's summed counter
 * goes backwards when a member leaves. A first sighting, a counter that went backwards (a broker
 * restart reusing an id) or a zero elapsed time yields {@code null}: unknown, never zero. A member
 * absent from a sweep is forgotten, so the interval it was last active in goes uncounted.
 *
 * <p>Not thread-safe: one instance per (cluster, node), used by one sweep at a time.
 */
final class MemberDeltas {

    /** One member's reading in a sweep: its lifetime counter and, for a consumer, its unacknowledged count. */
    record Reading(String memberId, long counter, long unacked) {}

    /**
     * @param rate messages per second since the previous sweep, or {@code null} when not computable
     * @param stalled unacknowledged messages outstanding and nothing acknowledged for {@value #STALL_SWEEPS} sweeps
     */
    record MemberRate(Double rate, boolean stalled) {}

    static final int STALL_SWEEPS = 2;

    private record Previous(long counter, Instant at, int idleSweeps) {}

    private Map<String, Previous> previous = new HashMap<>();

    Map<String, MemberRate> apply(Instant at, List<Reading> readings) {
        Map<String, Previous> next = new HashMap<>(readings.size() * 2);
        Map<String, MemberRate> out = new HashMap<>(readings.size() * 2);
        for (Reading r : readings) {
            Previous before = previous.get(r.memberId());
            Double rate = null;
            int idleSweeps = 0;
            if (before != null && r.counter() >= before.counter() && at.isAfter(before.at())) {
                long delta = r.counter() - before.counter();
                rate = delta / (Duration.between(before.at(), at).toMillis() / 1000.0);
                idleSweeps = delta == 0 && r.unacked() > 0 ? before.idleSweeps() + 1 : 0;
            }
            next.put(r.memberId(), new Previous(r.counter(), at, idleSweeps));
            out.put(r.memberId(), new MemberRate(rate, idleSweeps >= STALL_SWEEPS));
        }
        previous = next;
        return out;
    }
}
