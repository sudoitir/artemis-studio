package io.github.sudoitir.artemisstudio.feature.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.sudoitir.artemisstudio.ArtemisStudioApplication;
import io.github.sudoitir.artemisstudio.kernel.stream.SseHub;
import io.github.sudoitir.artemisstudio.kernel.stream.Subscriber;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerEvent;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Two replicas, this context and a second one started against the same database: what one publishes
 * reaches the stream clients of both, once each, and a batch of broker events reaches them with their
 * {@code seq} as the SSE id only after it has committed.
 */
class EventStreamBusTest extends PostgresIntegrationTest {

    @Autowired
    SseHub hubA;

    @Autowired
    BrokerEventWriter writer;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private final UUID clusterId = UUID.randomUUID();
    private ConfigurableApplicationContext other;
    private SseHub hubB;

    @BeforeEach
    void startTheOtherReplica() {
        List<String> properties = new ArrayList<>(connectionProperties());
        properties.add("server.port=0");
        other = new SpringApplicationBuilder(ArtemisStudioApplication.class)
                .run(properties.stream().map(property -> "--" + property).toArray(String[]::new));
        hubB = other.getBean(SseHub.class);
        jdbc.update(
                "INSERT INTO cluster (id, name) VALUES (:id, :name)",
                Map.of("id", clusterId, "name", "bus-" + clusterId));
    }

    @AfterEach
    void stopTheOtherReplica() {
        other.close();
        jdbc.update("DELETE FROM cluster WHERE id = :id", Map.of("id", clusterId));
    }

    @Test
    void aFramePublishedOnOneReplicaReachesTheClientsOfBothOnce() throws Exception {
        SseEmitter onA = subscribe(hubA, "queues");
        SseEmitter onB = subscribe(hubB, "queues");

        hubA.publish(clusterId, "queues");

        assertThat(frames(onA, 1)).singleElement().asString().contains("event:queues");
        assertThat(frames(onB, 1)).singleElement().asString().contains("event:queues");
    }

    @Test
    void brokerEventsReachBothReplicasWithTheirSeqAsIdOnceEach() throws Exception {
        SseEmitter onA = subscribe(hubA, "events");
        SseEmitter onB = subscribe(hubB, "events");

        writer.accept(event("CONSUMER_CREATED"));
        writer.accept(event("SESSION_CREATED"));
        writer.flush();

        List<String> seqs = jdbc.queryForList(
                "SELECT seq::text FROM broker_event WHERE cluster_id = :c ORDER BY broker_event.seq",
                Map.of("c", clusterId),
                String.class);
        assertThat(seqs).hasSize(2);
        for (SseEmitter emitter : List.of(onA, onB)) {
            List<String> frames = frames(emitter, 2);
            assertThat(frames.get(0)).contains("event:events", "id:" + seqs.get(0), "CONSUMER_CREATED");
            assertThat(frames.get(1)).contains("event:events", "id:" + seqs.get(1), "SESSION_CREATED");
        }
    }

    @Test
    void aRolledBackFlushReachesNoClient() throws Exception {
        SseEmitter onA = subscribe(hubA, "events");
        writer.accept(event("CONSUMER_CREATED"));

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            writer.flush();
            status.setRollbackOnly();
        });

        Thread.sleep(1_000);
        ArgumentCaptor<SseEmitter.SseEventBuilder> sent = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(onA, atLeast(0)).send(sent.capture());
        // Keep-alives may arrive; an events frame may not.
        assertThat(sent.getAllValues()).map(EventStreamBusTest::render).noneMatch(f -> f.contains("event:events"));
    }

    private SseEmitter subscribe(SseHub hub, String topic) {
        SseEmitter emitter = mock(SseEmitter.class);
        hub.register(clusterId, new Subscriber(emitter, Set.of(topic), null));
        return emitter;
    }

    /** The frames a client was sent. Waits for {@code expected} of them, then a moment more to catch a repeat. */
    private static List<String> frames(SseEmitter emitter, int expected) throws Exception {
        verify(emitter, timeout(5_000).times(expected)).send(any(SseEmitter.SseEventBuilder.class));
        Thread.sleep(500);
        ArgumentCaptor<SseEmitter.SseEventBuilder> sent = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(expected)).send(sent.capture());
        return sent.getAllValues().stream().map(EventStreamBusTest::render).toList();
    }

    private static String render(SseEmitter.SseEventBuilder builder) {
        StringBuilder out = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
            out.append(part.getData());
        }
        return out.toString();
    }

    private BrokerEvent event(String type) {
        return new BrokerEvent(
                clusterId,
                null,
                type,
                Instant.now(),
                "some.address",
                "rn",
                "c1",
                "s1",
                "conn1",
                "1.2.3.4:5",
                "alice",
                Map.of("_AMQ_NotifType", type));
    }
}
