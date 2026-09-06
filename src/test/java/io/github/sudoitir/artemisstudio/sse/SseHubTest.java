package io.github.sudoitir.artemisstudio.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseHubTest {

    private final SseHub hub = new SseHub();

    @Test
    void publishReachesOnlySubscribersOfThatTopic() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter queuesEmitter = mock(SseEmitter.class);
        SseEmitter topologyEmitter = mock(SseEmitter.class);
        hub.register(clusterId, new Subscriber(queuesEmitter, Set.of("queues")));
        hub.register(clusterId, new Subscriber(topologyEmitter, Set.of("topology")));

        hub.publish(clusterId, "queues");

        verify(queuesEmitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(topologyEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void aDeadEmitterIsDroppedOnTheNextPublish() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter dead = mock(SseEmitter.class);
        doThrow(new IOException("client gone")).when(dead).send(any(SseEmitter.SseEventBuilder.class));
        hub.register(clusterId, new Subscriber(dead, Set.of("queues")));
        assertThat(hub.subscriberCount(clusterId)).isEqualTo(1);

        hub.publish(clusterId, "queues");

        assertThat(hub.subscriberCount(clusterId)).isZero();
        verify(dead).completeWithError(any());
    }

    @Test
    void publishToAClusterWithNoSubscribersIsANoOp() {
        hub.publish(UUID.randomUUID(), "queues");
        // no exception
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
        hub.register(clusterId, new Subscriber(emitter, Set.of()));

        hub.heartbeat();

        ArgumentCaptor<SseEmitter.SseEventBuilder> frame = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(frame.capture());
        assertThat(render(frame.getValue())).contains("event:" + SseHub.PING);
    }

    @Test
    void aDeadEmitterIsDroppedOnTheHeartbeat() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter dead = mock(SseEmitter.class);
        doThrow(new IOException("client gone")).when(dead).send(any(SseEmitter.SseEventBuilder.class));
        hub.register(clusterId, new Subscriber(dead, Set.of("queues")));

        hub.heartbeat();

        assertThat(hub.subscriberCount(clusterId)).isZero();
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
        Subscriber s = new Subscriber(mock(SseEmitter.class), Set.of("topology"));
        hub.register(clusterId, s);
        hub.remove(clusterId, s);
        assertThat(hub.subscriberCount(clusterId)).isZero();
    }
}
