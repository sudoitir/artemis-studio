package io.github.sudoitir.artemisstudio.domain.rr;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Pure transition function for a flow already in {@link RrState#AWAITING_REPLY}
 * (design.md D2/D3). Terminal states never transition again from an
 * observation — a late reply on a resolved flow is recorded as an event, not a
 * state change. {@code TIMED_OUT}/{@code ORPHANED} from a deadline passing are
 * produced by the sweep, not this function: "nothing happened for long enough"
 * is not an observation.
 */
public final class FlowStateMachine {

    private FlowStateMachine() {}

    /**
     * Where a latency figure came from, which is part of the figure (ADR-0053).
     *
     * <p>{@code OBSERVED} is the gap between two sample ticks, so it is quantised to
     * the sample interval: two messages seen on the same tick measure zero.
     * {@code MESSAGE_TIMESTAMPS} is the gap between the messages' own enqueue times,
     * normalised onto Studio's clock — the real number, when the clocks that
     * produced it can be trusted.
     */
    public enum LatencySource {
        OBSERVED,
        MESSAGE_TIMESTAMPS
    }

    /**
     * The flow fields a transition decision needs — deliberately narrower than the
     * persistence entity.
     *
     * @param trustedRequestEnqueuedAt when the request says it was produced, on
     *     Studio's clock, and only when that claim survived the skew check. The
     *     caller passes null for a timestamp it does not trust, so an untrustworthy
     *     clock falls back to observation here rather than being re-litigated.
     */
    public record FlowContext(
            RrState state,
            Instant requestedAt,
            String requestMessageId,
            String replyDestination,
            Instant trustedRequestEnqueuedAt) {

        /** For the paths where no message timestamp is in play. */
        public FlowContext(RrState state, Instant requestedAt, String requestMessageId, String replyDestination) {
            this(state, requestedAt, requestMessageId, replyDestination, null);
        }
    }

    public record Transition(RrState next, String eventKind, Long latencyMs, LatencySource latencySource) {

        /** A transition that carries no latency, or one measured by observation. */
        public Transition(RrState next, String eventKind, Long latencyMs) {
            this(next, eventKind, latencyMs, LatencySource.OBSERVED);
        }
    }

    public static Optional<Transition> apply(FlowContext flow, Observation observation) {
        if (flow.state() != RrState.AWAITING_REPLY) {
            return Optional.empty();
        }
        return switch (observation) {
            case Observation.ReplySeen r -> Optional.of(completed(flow, r));

            case Observation.ResponderDown d
            when d.remainingConsumers() == 0 ->
                Optional.of(new Transition(RrState.RESPONDER_DROPPED, "RESPONDER_DROPPED", null));

            case Observation.MessageExpired e
            when e.messageId().equals(flow.requestMessageId()) ->
                Optional.of(new Transition(RrState.TIMED_OUT, "MESSAGE_EXPIRED", null));

            case Observation.TempQueueUnbound t
            when t.queueName().equals(flow.replyDestination()) ->
                Optional.of(new Transition(RrState.TIMED_OUT, "REQUESTER_GAVE_UP", null));

            default -> Optional.empty();
        };
    }

    /**
     * Prefer the messages' own clocks, and refuse an answer they cannot support.
     *
     * <p>A negative message-timestamp latency says the reply was produced before the
     * request it answers, which is impossible: the two timestamps came from
     * different clocks and at least one is wrong. It is never stored as a latency —
     * the flow falls back to observation, and the caller records the disagreement.
     */
    private static Transition completed(FlowContext flow, Observation.ReplySeen r) {
        Instant requestEnqueued = flow.trustedRequestEnqueuedAt();
        if (requestEnqueued != null && r.enqueuedAt() != null) {
            long fromMessages =
                    Duration.between(requestEnqueued, r.enqueuedAt()).toMillis();
            if (fromMessages >= 0) {
                return new Transition(RrState.COMPLETED, "REPLY_SEEN", fromMessages, LatencySource.MESSAGE_TIMESTAMPS);
            }
        }
        return new Transition(
                RrState.COMPLETED,
                "REPLY_SEEN",
                Duration.between(flow.requestedAt(), r.at()).toMillis(),
                LatencySource.OBSERVED);
    }
}
