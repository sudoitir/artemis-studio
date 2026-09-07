package io.github.sudoitir.artemisstudio.sql;

import io.github.sudoitir.artemisstudio.sql.QueryAst.Source;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything the executors need, and everything the operator is shown before the
 * query runs (ADR-0058 D6). A plan contacts no broker: it is built from
 * {@code queue_snapshot} and the measured clock offsets, so it is cheap enough to
 * recompute on every keystroke.
 */
public record QueryPlan(
        QueryAst ast,
        Source resolvedSource,
        List<Target> targets,
        String selector,
        boolean requiresScan,
        List<String> pushedDown,
        List<String> scanned,
        long estimatedMessagesExamined,
        int effectiveLimit,
        List<Notice> notices) {

    /**
     * One queue on one node. {@code brokerNow} is Studio's now shifted onto that
     * node's clock, so a relative window is expressed in the time the broker stamped
     * its own messages with (ADR-0053).
     */
    public record Target(
            UUID nodeId,
            String nodeName,
            String queueName,
            String address,
            String routingType,
            long messageCount,
            Instant brokerNow,
            boolean clockOffsetKnown) {}

    /**
     * Something true about this plan the operator has to be told. A notice is never a
     * reason to hide a result — it is the reason a result is not what it looks like.
     */
    public record Notice(Kind kind, String detail) {
        public enum Kind {
            /** The FROM pattern matched no queue at all — distinct from an empty result. */
            NO_QUEUE_MATCHED,
            /**
             * The pattern matched more queues than the target cap allows, so only
             * some of them were read. The opposite of NO_QUEUE_MATCHED: there were
             * too many, not none, and conflating the two inverts the fix.
             */
            TARGET_CAPPED,
            /** A queue matched the pattern but the caller cannot read it. */
            EXCLUDED_BY_PERMISSION,
            /** A relative window could not be normalised for a node's clock. */
            CLOCK_OFFSET_UNKNOWN,
            /** The index cannot answer for part of the requested window or queue set. */
            INDEX_COVERAGE_GAP,
            /** The query asks the live broker for a column only the index has. */
            INDEX_ONLY_COLUMN,
            /**
             * A body predicate was evaluated against a body the management channel
             * truncated, so a match may have been missed. Not a warning about style —
             * a statement that the result may be a false negative.
             */
            BODY_TRUNCATED,
            /** A node's browse changed channel mid-query, so fidelity is not uniform. */
            CHANNEL_CHANGED
        }
    }

    public boolean hasTargets() {
        return !targets.isEmpty();
    }
}
