package io.github.sudoitir.artemisstudio.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.domain.rr.Observation;
import io.github.sudoitir.artemisstudio.domain.rr.RrState;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.persist.RrEventRepository;
import io.github.sudoitir.artemisstudio.persist.RrExpectationEntity;
import io.github.sudoitir.artemisstudio.persist.RrExpectationRepository;
import io.github.sudoitir.artemisstudio.persist.RrFlowEntity;
import io.github.sudoitir.artemisstudio.persist.RrFlowRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link RrCorrelator} against a real Postgres: the full observation-to-flow lifecycle. */
class RrCorrelatorTest extends PostgresIntegrationTest {

    @Autowired
    RrCorrelator correlator;

    @Autowired
    RrFlowRepository flows;

    @Autowired
    RrEventRepository events;

    @Autowired
    RrExpectationRepository expectations;

    @Autowired
    ClusterRepository clusters;

    private UUID clusterId;

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private UUID cluster() {
        clusterId = clusters.save(new ClusterEntity("rr-corr-" + UUID.randomUUID(), null, null))
                .getId();
        return clusterId;
    }

    @Test
    void requestThenReplyCompletesTheFlow() {
        UUID clusterId = cluster();
        Instant t0 = Instant.now();

        correlator.accept(new Observation.RequestSeen(
                clusterId, null, t0, "rr.request", "m1", "corr-1", null, 0L, null, Map.of()));

        List<RrFlowEntity> created =
                flows.findByClusterIdAndRequestAddressAndState(clusterId, "rr.request", RrState.AWAITING_REPLY.name());
        assertThat(created).hasSize(1);
        assertThat(created.getFirst().getReplyKind()).isEqualTo("SHARED_QUEUE");

        correlator.accept(
                new Observation.ReplySeen(clusterId, null, t0.plusSeconds(2), null, "m2", "corr-1", null, Map.of()));

        RrFlowEntity flow = flows.findById(created.getFirst().getId()).orElseThrow();
        assertThat(flow.getState()).isEqualTo(RrState.COMPLETED.name());
        // Postgres timestamptz round-trips can lose sub-millisecond precision.
        assertThat(flow.getLatencyMs()).isCloseTo(2000L, org.assertj.core.data.Offset.offset(2L));
        assertThat(events.findByFlowIdOrderByTsAsc(flow.getId())).hasSize(2);
    }

    @Test
    void temporaryQueueReplyMatchesByDestination() {
        UUID clusterId = cluster();
        Instant t0 = Instant.now();

        correlator.accept(new Observation.RequestSeen(
                clusterId, null, t0, "rr.request", "m1", "corr-x", "temp-queue-abc", 0L, null, Map.of()));

        correlator.accept(new Observation.ReplySeen(
                clusterId, null, t0.plusMillis(500), "temp-queue-abc", "m2", null, null, Map.of()));

        RrFlowEntity flow = flows.findByClusterIdAndRequestAddressAndState(
                        clusterId, "rr.request", RrState.COMPLETED.name())
                .getFirst();
        assertThat(flow.getReplyKind()).isEqualTo("TEMP_QUEUE");
        // Postgres timestamptz round-trips can lose sub-millisecond precision.
        assertThat(flow.getLatencyMs()).isCloseTo(500L, org.assertj.core.data.Offset.offset(2L));
    }

    @Test
    void aReplyWithNoMatchingRequestIsOrphaned() {
        UUID clusterId = cluster();
        correlator.accept(new Observation.ReplySeen(
                clusterId, null, Instant.now(), "rr.reply", "m9", "no-such-correlation", null, Map.of()));

        List<RrFlowEntity> all = flows.findPage(
                        clusterId,
                        RrState.ORPHANED_REPLY.name(),
                        null,
                        null,
                        Instant.EPOCH,
                        Instant.parse("9999-12-31T23:59:59Z"),
                        org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent();
        assertThat(all).hasSize(1);
        assertThat(all.getFirst().getRequestAddress()).isNull();
    }

    @Test
    void theOnlyResponderDisappearingDropsAwaitingFlows() {
        UUID clusterId = cluster();
        Instant t0 = Instant.now();
        correlator.accept(new Observation.ResponderUp(clusterId, null, t0, "rr.request", "consumer-1"));
        correlator.accept(new Observation.RequestSeen(
                clusterId, null, t0.plusMillis(10), "rr.request", "m1", "corr-2", null, 0L, null, Map.of()));

        correlator.accept(
                new Observation.ResponderDown(clusterId, null, t0.plusMillis(20), "rr.request", "consumer-1", 0));

        RrFlowEntity flow = flows.findByClusterIdAndRequestAddressAndState(
                        clusterId, "rr.request", RrState.RESPONDER_DROPPED.name())
                .getFirst();
        assertThat(flow.getCorrelationId()).isEqualTo("corr-2");
    }

    @Test
    void deadlineResolutionPrefersMessageExpirationOverExpectation() {
        UUID clusterId = cluster();
        expectations.save(new RrExpectationEntity(clusterId, "rr.request", null, null, 99_000, 10, false));
        Instant t0 = Instant.now();
        long expirationEpochMs = t0.plusSeconds(3).toEpochMilli();

        correlator.accept(new Observation.RequestSeen(
                clusterId, null, t0, "rr.request", "m-deadline", "corr-3", null, expirationEpochMs, null, Map.of()));

        RrFlowEntity flow = flows.findByClusterIdAndRequestAddressAndRequestMessageId(
                        clusterId, "rr.request", "m-deadline")
                .orElseThrow();
        assertThat(flow.getDeadlineAt().toEpochMilli()).isEqualTo(expirationEpochMs);
    }

    @Test
    void aResponderAttachingAfterTheRequestIsBackfilledOntoTheOpenFlow() {
        // A request observed before any responder exists creates its flow with
        // responderConsumer null. A responder that attaches afterward, while the flow is
        // still awaiting reply, must be backfilled onto it — otherwise the deadline sweep
        // would misclassify an eventual timeout as ORPHANED instead of TIMED_OUT.
        UUID clusterId = cluster();
        Instant t0 = Instant.now();
        correlator.accept(new Observation.RequestSeen(
                clusterId, null, t0, "rr.request", "m-late-responder", "corr-4", null, 0L, null, Map.of()));

        correlator.accept(
                new Observation.ResponderUp(clusterId, null, t0.plusSeconds(1), "rr.request", "consumer-late"));

        RrFlowEntity flow = flows.findByClusterIdAndRequestAddressAndRequestMessageId(
                        clusterId, "rr.request", "m-late-responder")
                .orElseThrow();
        assertThat(flow.getResponderConsumer()).isEqualTo("consumer-late");
    }
}
