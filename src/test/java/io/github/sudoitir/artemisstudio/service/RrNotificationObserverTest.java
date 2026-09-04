package io.github.sudoitir.artemisstudio.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.broker.core.BrokerEvent;
import io.github.sudoitir.artemisstudio.domain.rr.Observation;
import io.github.sudoitir.artemisstudio.domain.rr.RrState;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.persist.RrExpectationEntity;
import io.github.sudoitir.artemisstudio.persist.RrExpectationRepository;
import io.github.sudoitir.artemisstudio.persist.RrFlowEntity;
import io.github.sudoitir.artemisstudio.persist.RrFlowRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link RrNotificationObserver} against a real correlator/Postgres: only
 * notifications relevant to a traced address or an open temp-queue flow reach
 * the correlator — broker-wide chatter on unrelated addresses does not.
 */
class RrNotificationObserverTest extends PostgresIntegrationTest {

    @Autowired
    RrNotificationObserver observer;

    @Autowired
    RrCorrelator correlator;

    @Autowired
    RrFlowRepository flows;

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
        clusterId = clusters.save(new ClusterEntity("rr-notif-" + UUID.randomUUID(), null, null))
                .getId();
        return clusterId;
    }

    private BrokerEvent event(
            UUID clusterId, String type, String address, String routingName, Map<String, Object> props) {
        return new BrokerEvent(
                clusterId,
                null,
                type,
                Instant.now(),
                address,
                routingName,
                "consumer-1",
                "s1",
                "c1",
                "10.0.0.1:1",
                "u",
                props);
    }

    @Test
    void unrelatedConsumerActivityIsIgnored() {
        UUID clusterId = cluster();
        // No expectation declared for "some.other.address" — must not be tracked.
        observer.accept(event(clusterId, "CONSUMER_CREATED", "some.other.address", "some.other.address", Map.of()));

        assertThat(correlator.isTracedRequestAddress(clusterId, "some.other.address"))
                .isFalse();
    }

    @Test
    void responderDropOnAnUntracedAddressIsIgnored() {
        UUID clusterId = cluster();
        Instant t0 = Instant.now();
        correlator.accept(new Observation.RequestSeen(
                clusterId, null, t0, "rr.request", "m1", "corr-1", null, 0L, null, Map.of()));

        // No expectation declared for "rr.request" — isTracedRequestAddress is false, so this must be ignored.
        observer.accept(
                event(clusterId, "CONSUMER_CLOSED", "rr.request", "rr.request", Map.of("_AMQ_ConsumerCount", 0)));

        RrFlowEntity flow = flows.findByClusterIdAndRequestAddressAndRequestMessageId(clusterId, "rr.request", "m1")
                .orElseThrow();
        assertThat(flow.getState()).isEqualTo(RrState.AWAITING_REPLY.name());
    }

    @Test
    void responderDropOnATracedAddressDropsTheFlow() {
        UUID clusterId = cluster();
        expectations.save(new RrExpectationEntity(clusterId, "rr.traced", null, null, null, 10, false));
        Instant t0 = Instant.now();
        correlator.accept(new Observation.RequestSeen(
                clusterId, null, t0, "rr.traced", "m2", "corr-1", null, 0L, null, Map.of()));

        observer.accept(event(clusterId, "CONSUMER_CLOSED", "rr.traced", "rr.traced", Map.of("_AMQ_ConsumerCount", 0)));

        RrFlowEntity flow = flows.findByClusterIdAndRequestAddressAndRequestMessageId(clusterId, "rr.traced", "m2")
                .orElseThrow();
        assertThat(flow.getState()).isEqualTo(RrState.RESPONDER_DROPPED.name());
    }

    @Test
    void messageDeliveredOnAKnownOpenTempQueueCompletesTheFlow() {
        UUID clusterId = cluster();
        Instant t0 = Instant.now();
        correlator.accept(new Observation.RequestSeen(
                clusterId, null, t0, "rr.request", "m1", "corr-2", "temp-queue-xyz", 0L, null, Map.of()));

        observer.accept(event(
                clusterId, "MESSAGE_DELIVERED", "temp-queue-xyz", "temp-queue-xyz", Map.of("_AMQ_Message_ID", "42")));

        RrFlowEntity flow = flows.findByClusterIdAndRequestAddressAndRequestMessageId(clusterId, "rr.request", "m1")
                .orElseThrow();
        assertThat(flow.getState()).isEqualTo(RrState.COMPLETED.name());
    }

    @Test
    void messageDeliveredOnAnUnrelatedAddressCreatesNoFlow() {
        UUID clusterId = cluster();
        observer.accept(event(
                clusterId, "MESSAGE_DELIVERED", "unrelated.queue", "unrelated.queue", Map.of("_AMQ_Message_ID", "99")));

        assertThat(flows.findByClusterIdAndRequestAddressAndState(
                        clusterId, "unrelated.queue", RrState.ORPHANED_REPLY.name()))
                .isEmpty();
    }

    @Test
    void notificationsAboutTheNotificationAddressItselfAreIgnored() {
        UUID clusterId = cluster();
        observer.accept(event(clusterId, "CONSUMER_CREATED", "activemq.notifications", "some-uuid", Map.of()));
        // No exception, no side effect — nothing to assert beyond "did not throw".
    }
}
