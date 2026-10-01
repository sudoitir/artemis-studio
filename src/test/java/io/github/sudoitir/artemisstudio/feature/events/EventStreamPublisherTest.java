package io.github.sudoitir.artemisstudio.feature.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.feature.events.internal.persistence.BrokerEventEntity;
import io.github.sudoitir.artemisstudio.feature.events.internal.persistence.BrokerEventRepository;
import io.github.sudoitir.artemisstudio.kernel.replica.BusEvents;
import io.github.sudoitir.artemisstudio.kernel.replica.BusFrame;
import io.github.sudoitir.artemisstudio.kernel.replica.StudioBus;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** The row load for an announced batch runs off the bus dispatch thread and keeps batch order. */
class EventStreamPublisherTest {

    private final UUID clusterId = UUID.randomUUID();
    private final List<Object> frames = new CopyOnWriteArrayList<>();
    private final BrokerEventRepository repository = mock(BrokerEventRepository.class);
    private final BrokerEventService service = mock(BrokerEventService.class);
    private final EventStreamPublisher publisher = new EventStreamPublisher(
            mock(StudioBus.class), frames::add, new ObjectMapper(), mock(TopicCoalescer.class), service, repository);

    private BrokerEventEntity row(long seq) {
        BrokerEventEntity e = mock(BrokerEventEntity.class);
        when(e.getSeq()).thenReturn(seq);
        when(e.getClusterId()).thenReturn(clusterId);
        return e;
    }

    @Test
    void aSlowLoadDoesNotBlockTheCallerAndBatchesGoOutInOrder() {
        CountDownLatch slow = new CountDownLatch(1);
        when(repository.findBySeqInOrderBySeqAsc(anyList())).thenAnswer(invocation -> {
            List<Long> seqs = invocation.getArgument(0);
            if (seqs.getFirst() == 1L) {
                slow.await();
            }
            return seqs.stream().map(this::row).toList();
        });

        long start = System.nanoTime();
        publisher.on(new BusEvents(List.of(1L, 2L)));
        publisher.on(new BusEvents(List.of(3L)));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        slow.countDown();

        await().atMost(Duration.ofSeconds(5)).until(() -> frames.size() == 3);
        assertThat(frames).map(f -> ((BusFrame) f).id()).containsExactly("1", "2", "3");
        publisher.close();
    }
}
