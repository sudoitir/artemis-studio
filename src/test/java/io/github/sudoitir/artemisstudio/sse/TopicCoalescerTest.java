package io.github.sudoitir.artemisstudio.sse;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties.Events;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TopicCoalescerTest {

    private final SseHub hub = mock(SseHub.class);
    private final ArtemisStudioProperties props = new ArtemisStudioProperties(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new Events(Duration.ofHours(72), 100, Duration.ofSeconds(1), 50),
            null,
            null);
    private final TopicCoalescer coalescer = new TopicCoalescer(hub, props);

    @Test
    void aBurstYieldsExactlyOnePublishPerWindow() {
        UUID clusterId = UUID.randomUUID();

        for (int i = 0; i < 500; i++) {
            coalescer.touch(clusterId, "consumers");
        }

        verify(hub, timeout(1_000).times(1)).publish(eq(clusterId), eq("consumers"));

        // A fresh touch after the window fires again.
        coalescer.touch(clusterId, "consumers");
        verify(hub, timeout(1_000).times(2)).publish(eq(clusterId), eq("consumers"));
    }

    @Test
    void aNullTopicIsIgnored() {
        coalescer.touch(UUID.randomUUID(), null);
        verify(hub, times(0)).publish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
