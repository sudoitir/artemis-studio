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

    /** The flow fields a transition decision needs — deliberately narrower than the persistence entity. */
    public record FlowContext(RrState state, Instant requestedAt, String requestMessageId, String replyDestination) {}

    public record Transition(RrState next, String eventKind, Long latencyMs) {}

    public static Optional<Transition> apply(FlowContext flow, Observation observation) {
        if (flow.state() != RrState.AWAITING_REPLY) {
            return Optional.empty();
        }
        return switch (observation) {
            case Observation.ReplySeen r ->
                Optional.of(new Transition(
                        RrState.COMPLETED,
                        "REPLY_SEEN",
                        Duration.between(flow.requestedAt(), r.at()).toMillis()));

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
}
