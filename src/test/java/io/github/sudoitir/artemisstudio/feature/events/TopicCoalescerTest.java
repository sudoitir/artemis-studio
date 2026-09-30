package io.github.sudoitir.artemisstudio.feature.events;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.sudoitir.artemisstudio.kernel.stream.SseHub;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TopicCoalescerTest {

    private final SseHub hub = mock(SseHub.class);
    private final EventsProperties props = new EventsProperties(100, Duration.ofSeconds(1), 50);
    private final TopicCoalescer coalescer = new TopicCoalescer(hub, props);

    @Test
    void aBurstYieldsExactlyOnePublishPerWindow() {
        UUID clusterId = UUID.randomUUID();

        for (int i = 0; i < 500; i++) {
            coalescer.touch(clusterId, "consumers");
        }

        verify(hub, timeout(1_000).times(1)).publish(clusterId, "consumers");

        // A fresh touch after the window fires again.
        coalescer.touch(clusterId, "consumers");
        verify(hub, timeout(1_000).times(2)).publish(clusterId, "consumers");
    }

    @Test
    void aNullTopicIsIgnored() {
        coalescer.touch(UUID.randomUUID(), null);
        verify(hub, times(0)).publish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
