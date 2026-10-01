package io.github.sudoitir.artemisstudio.kernel.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.sudoitir.artemisstudio.kernel.replica.BusFrame;
import io.github.sudoitir.artemisstudio.kernel.replica.BusMessage;
import io.github.sudoitir.artemisstudio.kernel.replica.BusResumed;
import io.github.sudoitir.artemisstudio.kernel.replica.StudioBus;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

class SseHubTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StudioBus bus = mock(StudioBus.class);
    private final SseHub hub = new SseHub(bus, mapper);

    /** What the bus would do: hand the message back to this replica, which is how every frame arrives. */
    private void loopback() {
        doAnswer(invocation -> {
                    hub.onFrame((BusFrame) invocation.getArgument(0));
                    return null;
                })
                .when(bus)
                .publish(any(BusMessage.class));
    }

    @Test
    void publishReachesOnlySubscribersOfThatTopic() throws IOException {
        loopback();
        UUID clusterId = UUID.randomUUID();
        SseEmitter queuesEmitter = mock(SseEmitter.class);
        SseEmitter topologyEmitter = mock(SseEmitter.class);
        hub.register(clusterId, new Subscriber(queuesEmitter, Set.of("queues"), null));
        hub.register(clusterId, new Subscriber(topologyEmitter, Set.of("topology"), null));

        hub.publish(clusterId, "queues");

        verify(queuesEmitter, timeout(2_000)).send(any(SseEmitter.SseEventBuilder.class));
        verify(topologyEmitter, after(200).never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void aDeadEmitterIsDroppedOnTheNextPublish() throws IOException {
        loopback();
        UUID clusterId = UUID.randomUUID();
        SseEmitter dead = mock(SseEmitter.class);
        doThrow(new IOException("client gone")).when(dead).send(any(SseEmitter.SseEventBuilder.class));
        hub.register(clusterId, new Subscriber(dead, Set.of("queues"), null));
        assertThat(hub.subscriberCount(clusterId)).isEqualTo(1);

        hub.publish(clusterId, "queues");

        verify(dead, timeout(2_000)).completeWithError(any());
        assertThat(hub.subscriberCount(clusterId)).isZero();
    }

    @Test
    void publishToAClusterWithNoSubscribersIsANoOp() {
        loopback();
        UUID clusterId = UUID.randomUUID();

        assertThatCode(() -> hub.publish(clusterId, "queues")).doesNotThrowAnyException();

        assertThat(hub.subscriberCount(clusterId)).isZero();
    }

    /**
     * The client's silence watchdog needs a frame to miss. A comment keeps the socket
     * warm and fires nothing an {@code EventSource} can observe (ADR-0052).
     */
    @Test
    void theHeartbeatIsANamedEventEverySubscriberReceives() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        // Subscribed to nothing: the keep-alive is not a topic.
        hub.register(clusterId, new Subscriber(emitter, Set.of(), null));

        hub.heartbeat();

        ArgumentCaptor<SseEmitter.SseEventBuilder> frame = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, timeout(2_000)).send(frame.capture());
        assertThat(render(frame.getValue())).contains("event:" + SseHub.PING);
    }

    @Test
    void aNewSubscriberIsGreetedWithAPingSoItsClientSeesTheStreamOpenAtOnce() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);

        hub.greet(new Subscriber(emitter, Set.of("queues"), null));

        ArgumentCaptor<SseEmitter.SseEventBuilder> frame = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(frame.capture());
        assertThat(render(frame.getValue())).contains("event:" + SseHub.PING);
    }

    @Test
    void aDeadEmitterIsDroppedOnTheHeartbeat() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter dead = mock(SseEmitter.class);
        doThrow(new IOException("client gone")).when(dead).send(any(SseEmitter.SseEventBuilder.class));
        hub.register(clusterId, new Subscriber(dead, Set.of("queues"), null));

        hub.heartbeat();

        await().atMost(Duration.ofSeconds(2)).until(() -> hub.subscriberCount(clusterId) == 0);
    }

    @Test
    void publishBroadcastsAFrameAndDeliversNothingItself() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        hub.register(clusterId, new Subscriber(emitter, Set.of("events"), null));

        hub.publish(clusterId, "events", Map.of("seq", 7), "7");

        verify(bus).publish(new BusFrame(clusterId, "events", mapper.readTree("{\"seq\":7}"), "7"));
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void aSignalIsBroadcastWithoutData() {
        UUID clusterId = UUID.randomUUID();

        hub.publish(clusterId, "queues");

        verify(bus).publish(new BusFrame(clusterId, "queues", null, null));
    }

    @Test
    void aDatabaseThatCannotTakeASignalDoesNotFailThePublisher() {
        UUID clusterId = UUID.randomUUID();
        doThrow(new CannotGetJdbcConnectionException("pool exhausted"))
                .when(bus)
                .publish(any(BusMessage.class));

        assertThatCode(() -> {
                    hub.publish(clusterId, "queues");
                    hub.publish(clusterId, "queues");
                    hub.publish(clusterId, "events", Map.of("seq", 7), "7");
                })
                .doesNotThrowAnyException();

        verify(bus, times(3)).publish(any(BusMessage.class));
    }

    @Test
    void aFrameThatArrivesIsDeliveredOnceToTheLocalSubscribersOfItsTopic() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter wants = mock(SseEmitter.class);
        SseEmitter other = mock(SseEmitter.class);
        hub.register(clusterId, new Subscriber(wants, Set.of("events"), null));
        hub.register(clusterId, new Subscriber(other, Set.of("queues"), null));

        hub.onFrame(new BusFrame(clusterId, "events", mapper.readTree("{\"seq\":7}"), "7"));

        ArgumentCaptor<SseEmitter.SseEventBuilder> frame = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(wants, timeout(2_000)).send(frame.capture());
        assertThat(render(frame.getValue())).contains("event:events", "id:7");
        verify(other, after(200).never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void aBufferingSubscriberGetsLiveFramesAfterTheReplayAndNoRepeat() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        Subscriber subscriber = new Subscriber(emitter, Set.of("events", "queues"), null);
        subscriber.buffer();
        hub.register(clusterId, subscriber);
        hub.sendTo(subscriber, "events", "replayed", "11");
        hub.sendTo(subscriber, "events", "replayed", "12");

        hub.onFrame(new BusFrame(clusterId, "events", mapper.readTree("12"), "12"));
        hub.onFrame(new BusFrame(clusterId, "queues", null, null));
        hub.onFrame(new BusFrame(clusterId, "events", mapper.readTree("13"), "13"));
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));

        hub.release(subscriber, Map.of("events", 12L));
        hub.onFrame(new BusFrame(clusterId, "events", mapper.readTree("14"), "14"));

        ArgumentCaptor<SseEmitter.SseEventBuilder> sent = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, timeout(2_000).times(5)).send(sent.capture());
        assertThat(sent.getAllValues().stream().map(SseHubTest::render))
                .satisfiesExactly(
                        a -> assertThat(a).contains("id:11"),
                        a -> assertThat(a).contains("id:12"),
                        a -> assertThat(a).contains("event:queues"),
                        a -> assertThat(a).contains("id:13"),
                        a -> assertThat(a).contains("id:14"));
    }

    @Test
    void theBusComingBackTellsEverySubscriberToResync() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        hub.register(clusterId, new Subscriber(emitter, Set.of(), null));

        hub.onBusResumed(new BusResumed());

        ArgumentCaptor<SseEmitter.SseEventBuilder> frame = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, timeout(2_000)).send(frame.capture());
        assertThat(render(frame.getValue())).contains("event:" + SseHub.RESYNC);
    }

    @Test
    void closingTellsEverySubscriberToReconnectBeforeItIsCompleted() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        hub.register(clusterId, new Subscriber(emitter, Set.of(), null));

        hub.closeAll();

        InOrder order = inOrder(emitter);
        ArgumentCaptor<SseEmitter.SseEventBuilder> frame = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        order.verify(emitter, timeout(2_000)).send(frame.capture());
        order.verify(emitter, timeout(2_000)).complete();
        assertThat(render(frame.getValue())).contains("event:" + SseHub.RECONNECT);
        assertThat(hub.clientCount()).isZero();
    }

    @Test
    void aSubscriberWhoseEmitterBlocksDelaysNobody() throws Exception {
        UUID clusterId = UUID.randomUUID();
        CountDownLatch stall = new CountDownLatch(1);
        SseEmitter stalled = mock(SseEmitter.class);
        doAnswer(invocation -> {
                    stall.await();
                    return null;
                })
                .when(stalled)
                .send(any(SseEmitter.SseEventBuilder.class));
        SseEmitter healthy = mock(SseEmitter.class);
        hub.register(clusterId, new Subscriber(stalled, Set.of("queues"), null));
        hub.register(clusterId, new Subscriber(healthy, Set.of("queues"), null));

        long start = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            hub.onFrame(new BusFrame(clusterId, "queues", null, null));
        }

        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("dispatch").isLessThan(Duration.ofSeconds(1));
        verify(healthy, timeout(2_000).times(3)).send(any(SseEmitter.SseEventBuilder.class));
        stall.countDown();
    }

    @Test
    void aSubscriberThatFallsTooFarBehindLosesItsQueueToOneResync() throws Exception {
        UUID clusterId = UUID.randomUUID();
        int total = Subscriber.OUTBOUND_CAPACITY + 500;
        CountDownLatch stall = new CountDownLatch(1);
        List<String> sent = new CopyOnWriteArrayList<>();
        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(invocation -> {
                    stall.await();
                    sent.add(render(invocation.getArgument(0)));
                    return null;
                })
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        hub.register(clusterId, new Subscriber(emitter, Set.of("events"), null));

        for (int i = 0; i < total; i++) {
            hub.onFrame(new BusFrame(clusterId, "events", mapper.readTree(Integer.toString(i)), Integer.toString(i)));
        }
        stall.countDown();

        String last = "id:" + (total - 1);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> !sent.isEmpty() && sent.getLast().contains(last));
        assertThat(sent)
                .as("resync frames")
                .filteredOn(f -> f.contains("event:resync"))
                .hasSize(1);
        assertThat(sent).as("frames written").hasSizeLessThan(total);
    }

    @Test
    void framesAreWrittenInTheOrderTheyArrived() throws Exception {
        UUID clusterId = UUID.randomUUID();
        List<String> sent = new CopyOnWriteArrayList<>();
        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(invocation -> {
                    sent.add(render(invocation.getArgument(0)));
                    return null;
                })
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        hub.register(clusterId, new Subscriber(emitter, Set.of("events"), null));

        for (int i = 0; i < 300; i++) {
            hub.onFrame(new BusFrame(clusterId, "events", mapper.readTree(Integer.toString(i)), Integer.toString(i)));
        }

        await().atMost(Duration.ofSeconds(5)).until(() -> sent.size() == 300);
        for (int i = 0; i < 300; i++) {
            assertThat(sent.get(i)).contains("id:" + i + "\n");
        }
    }

    private static String render(SseEmitter.SseEventBuilder builder) {
        StringBuilder out = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
            out.append(part.getData());
        }
        return out.toString();
    }

    @Test
    void removeDeregistersOneSubscriber() {
        UUID clusterId = UUID.randomUUID();
        Subscriber s = new Subscriber(mock(SseEmitter.class), Set.of("topology"), null);
        hub.register(clusterId, s);
        hub.remove(clusterId, s);
        assertThat(hub.subscriberCount(clusterId)).isZero();
    }
}
