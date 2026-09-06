package io.github.sudoitir.artemisstudio.domain.rr;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.domain.rr.FlowStateMachine.FlowContext;
import io.github.sudoitir.artemisstudio.domain.rr.FlowStateMachine.Transition;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Table-driven: every (state, observation) pair the design commits to, including the ones that must not transition. */
class FlowStateMachineTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final Instant REQUESTED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static FlowContext awaiting() {
        return new FlowContext(RrState.AWAITING_REPLY, REQUESTED_AT, "req-msg-1", "temp.reply.q");
    }

    private static Observation.ReplySeen reply(Instant at, Instant enqueuedAt) {
        return new Observation.ReplySeen(
                CLUSTER,
                UUID.randomUUID(),
                at,
                "temp.reply.q",
                "reply-msg-1",
                "corr-1",
                null,
                java.util.Map.of(),
                enqueuedAt);
    }

    @Test
    void twoMessagesSeenOnOneTickStillHaveARealLatency() {
        // The observed figure is the gap between sample ticks, so a request and its
        // reply read on the same tick measured ~0ms. The messages' own clocks know
        // better, and after normalisation they are on Studio's timeline (ADR-0053).
        Instant sameTick = REQUESTED_AT.plusSeconds(4);
        FlowContext flow = new FlowContext(
                RrState.AWAITING_REPLY, sameTick, "req-msg-1", "temp.reply.q", REQUESTED_AT.plusMillis(100));

        Transition t = FlowStateMachine.apply(flow, reply(sameTick, REQUESTED_AT.plusMillis(412)))
                .orElseThrow();

        assertThat(t.latencyMs()).isEqualTo(312L);
        assertThat(t.latencySource()).isEqualTo(FlowStateMachine.LatencySource.MESSAGE_TIMESTAMPS);
    }

    @Test
    void aReplyThatClaimsToPredateItsRequestIsNotALatency() {
        // Impossible, so at least one of the two clocks is wrong. Storing the negative
        // number would put a lie in the percentile window; observation is used instead
        // and the caller records the disagreement.
        FlowContext flow = new FlowContext(
                RrState.AWAITING_REPLY, REQUESTED_AT, "req-msg-1", "temp.reply.q", REQUESTED_AT.plusSeconds(10));

        Transition t = FlowStateMachine.apply(flow, reply(REQUESTED_AT.plusSeconds(5), REQUESTED_AT))
                .orElseThrow();

        assertThat(t.latencyMs()).isEqualTo(5000L);
        assertThat(t.latencySource()).isEqualTo(FlowStateMachine.LatencySource.OBSERVED);
    }

    @Test
    void aRequestTimestampTheCallerDoesNotTrustFallsBackToObservation() {
        // The correlator passes null for a timestamp that already failed the skew
        // check, so an untrustworthy clock is refused once rather than re-litigated.
        Transition t = FlowStateMachine.apply(awaiting(), reply(REQUESTED_AT.plusSeconds(5), REQUESTED_AT))
                .orElseThrow();

        assertThat(t.latencySource()).isEqualTo(FlowStateMachine.LatencySource.OBSERVED);
        assertThat(t.latencyMs()).isEqualTo(5000L);
    }

    @Test
    void replySeenCompletesWithLatency() {
        Instant repliedAt = REQUESTED_AT.plusSeconds(5);
        Optional<Transition> t = FlowStateMachine.apply(
                awaiting(),
                new Observation.ReplySeen(
                        CLUSTER,
                        UUID.randomUUID(),
                        repliedAt,
                        "temp.reply.q",
                        "reply-msg-1",
                        "corr-1",
                        null,
                        java.util.Map.of()));

        assertThat(t).isPresent();
        assertThat(t.get().next()).isEqualTo(RrState.COMPLETED);
        assertThat(t.get().eventKind()).isEqualTo("REPLY_SEEN");
        assertThat(t.get().latencyMs()).isEqualTo(5000L);
    }

    @Test
    void lastResponderDisappearingDropsTheFlow() {
        Optional<Transition> t = FlowStateMachine.apply(
                awaiting(),
                new Observation.ResponderDown(
                        CLUSTER, UUID.randomUUID(), REQUESTED_AT.plusSeconds(1), "rr.request", "consumer-1", 0));

        assertThat(t).contains(new Transition(RrState.RESPONDER_DROPPED, "RESPONDER_DROPPED", null));
    }

    @Test
    void responderDownWithRemainingConsumersDoesNotTransition() {
        Optional<Transition> t = FlowStateMachine.apply(
                awaiting(),
                new Observation.ResponderDown(
                        CLUSTER, UUID.randomUUID(), REQUESTED_AT.plusSeconds(1), "rr.request", "consumer-1", 1));

        assertThat(t).isEmpty();
    }

    @Test
    void matchingMessageExpiredTimesOut() {
        Optional<Transition> t = FlowStateMachine.apply(
                awaiting(),
                new Observation.MessageExpired(
                        CLUSTER, UUID.randomUUID(), REQUESTED_AT.plusSeconds(2), "rr.request", "req-msg-1"));

        assertThat(t).contains(new Transition(RrState.TIMED_OUT, "MESSAGE_EXPIRED", null));
    }

    @Test
    void expiredMessageForADifferentRequestIsIgnored() {
        Optional<Transition> t = FlowStateMachine.apply(
                awaiting(),
                new Observation.MessageExpired(
                        CLUSTER, UUID.randomUUID(), REQUESTED_AT.plusSeconds(2), "rr.request", "some-other-msg"));

        assertThat(t).isEmpty();
    }

    @Test
    void ownReplyQueueUnboundTimesOut() {
        Optional<Transition> t = FlowStateMachine.apply(
                awaiting(),
                new Observation.TempQueueUnbound(
                        CLUSTER, UUID.randomUUID(), REQUESTED_AT.plusSeconds(1), "temp.reply.q"));

        assertThat(t).contains(new Transition(RrState.TIMED_OUT, "REQUESTER_GAVE_UP", null));
    }

    @Test
    void unrelatedQueueUnboundIsIgnored() {
        Optional<Transition> t = FlowStateMachine.apply(
                awaiting(),
                new Observation.TempQueueUnbound(
                        CLUSTER, UUID.randomUUID(), REQUESTED_AT.plusSeconds(1), "some.other.q"));

        assertThat(t).isEmpty();
    }

    @Test
    void responderUpNeverTransitions() {
        Optional<Transition> t = FlowStateMachine.apply(
                awaiting(),
                new Observation.ResponderUp(
                        CLUSTER, UUID.randomUUID(), REQUESTED_AT.plusSeconds(1), "rr.request", "consumer-1"));

        assertThat(t).isEmpty();
    }

    @Test
    void terminalStatesNeverTransitionAgain() {
        for (RrState terminal : new RrState[] {
            RrState.COMPLETED, RrState.TIMED_OUT, RrState.ORPHANED, RrState.RESPONDER_DROPPED, RrState.ORPHANED_REPLY
        }) {
            FlowContext ctx = new FlowContext(terminal, REQUESTED_AT, "req-msg-1", "temp.reply.q");
            Optional<Transition> t = FlowStateMachine.apply(
                    ctx,
                    new Observation.ReplySeen(
                            CLUSTER,
                            UUID.randomUUID(),
                            REQUESTED_AT.plusSeconds(5),
                            "temp.reply.q",
                            "m",
                            "c",
                            null,
                            java.util.Map.of()));
            assertThat(t).as("terminal state %s must not transition", terminal).isEmpty();
        }
    }
}
