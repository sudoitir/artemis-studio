package io.github.sudoitir.artemisstudio.broker;

import io.github.sudoitir.artemisstudio.broker.ClockOffsetRegistry.ClockOffset;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Puts a timestamp that came from a broker onto Studio's timeline (ADR-0053).
 *
 * <p>Every instant Studio persists is compared against Studio's own clock sooner or
 * later — a deadline sweep, a latency subtraction, an "observed at" ordering. A
 * value stamped by a broker whose clock is ten minutes fast is not wrong on that
 * broker; it is wrong here. Subtracting the measured offset at the boundary means
 * a wrong broker clock is absorbed once, at the edge, rather than corrupting every
 * downstream comparison.
 *
 * <p>With no measurement for a node the instant is passed through unchanged. That
 * is the honest default: no measurement means no evidence of skew, and inventing a
 * correction would be worse than applying none.
 */
public final class BrokerTime {

    private final Function<UUID, Optional<ClockOffset>> offsets;

    public BrokerTime(Function<UUID, Optional<ClockOffset>> offsets) {
        this.offsets = offsets;
    }

    /**
     * @param nodeId the node whose clock produced {@code brokerEpochMillis}
     * @return the same moment, expressed on Studio's clock
     */
    public Instant toStudioTime(UUID nodeId, long brokerEpochMillis) {
        return Instant.ofEpochMilli(brokerEpochMillis - correctionFor(nodeId));
    }

    /** As {@link #toStudioTime}, for a value already parsed. */
    public Instant toStudioTime(UUID nodeId, Instant brokerInstant) {
        return brokerInstant.minusMillis(correctionFor(nodeId));
    }

    /**
     * How much to subtract from this node's timestamps, in milliseconds.
     *
     * <p>Zero unless the offset is larger than its own uncertainty: correcting by
     * an amount smaller than the error bar is noise dressed as precision, and with
     * Jolokia's second-granularity timestamps that error bar is never below half a
     * second.
     */
    public long correctionFor(UUID nodeId) {
        return offsets.apply(nodeId)
                .filter(ClockOffset::isMeaningful)
                .map(ClockOffset::offsetMs)
                .orElse(0L);
    }

    /** A pass-through, for tests and for paths where no node is known. */
    public static BrokerTime identity() {
        return new BrokerTime(id -> Optional.empty());
    }
}
