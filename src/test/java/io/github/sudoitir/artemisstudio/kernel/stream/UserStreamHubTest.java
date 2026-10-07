package io.github.sudoitir.artemisstudio.kernel.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.github.sudoitir.artemisstudio.kernel.replica.BusResumed;
import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaSignal;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class UserStreamHubTest {

    private final UserStreamHub hub = new UserStreamHub();

    private static Subscriber subscriber(SseEmitter emitter, String sessionId) {
        return new Subscriber(emitter, Set.of(), sessionId, null, null);
    }

    @Test
    void aSignalReachesOnlyThatUsersStreams() throws IOException {
        UUID alice = UUID.randomUUID();
        SseEmitter aliceEmitter = mock(SseEmitter.class);
        SseEmitter bobEmitter = mock(SseEmitter.class);
        hub.register(alice, subscriber(aliceEmitter, null));
        hub.register(UUID.randomUUID(), subscriber(bobEmitter, null));

        hub.onSignal(new ReplicaSignal(UserSignals.INBOX, alice.toString()));

        verify(aliceEmitter, timeout(2_000)).send(any(SseEmitter.SseEventBuilder.class));
        verify(bobEmitter, after(200).never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void aSixthStreamIsRefused() {
        UUID user = UUID.randomUUID();
        for (int i = 0; i < UserStreamHub.MAX_STREAMS_PER_USER; i++) {
            assertThat(hub.register(user, subscriber(mock(SseEmitter.class), null)))
                    .isTrue();
        }

        assertThat(hub.register(user, subscriber(mock(SseEmitter.class), null))).isFalse();
        assertThat(hub.streamCount(user)).isEqualTo(UserStreamHub.MAX_STREAMS_PER_USER);
    }

    @Test
    void aRemovedStreamFreesItsPlace() {
        UUID user = UUID.randomUUID();
        Subscriber first = subscriber(mock(SseEmitter.class), null);
        hub.register(user, first);

        hub.remove(user, first);

        assertThat(hub.streamCount(user)).isZero();
    }

    @Test
    void aResumedBusTellsEveryStreamToResync() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        hub.register(UUID.randomUUID(), subscriber(emitter, null));

        hub.onBusResumed(new BusResumed());

        verify(emitter, timeout(2_000)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void anEndedSessionClosesItsStreamsOnly() {
        UUID user = UUID.randomUUID();
        hub.register(user, subscriber(mock(SseEmitter.class), "ended"));
        hub.register(user, subscriber(mock(SseEmitter.class), "live"));

        hub.onSessionEnded(new ReplicaSignal("session-ended", "ended"));

        assertThat(hub.streamCount(user)).isEqualTo(1);
    }

    @Test
    void aMalformedKeyIsIgnored() {
        hub.onSignal(new ReplicaSignal(UserSignals.HELD, "not-a-uuid"));
    }
}
