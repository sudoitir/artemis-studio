package io.github.sudoitir.artemisstudio.feature.events;

import io.github.sudoitir.artemisstudio.feature.events.internal.persistence.BrokerEventEntity;
import io.github.sudoitir.artemisstudio.feature.events.internal.persistence.BrokerEventRepository;
import io.github.sudoitir.artemisstudio.kernel.replica.BusEvents;
import io.github.sudoitir.artemisstudio.kernel.replica.BusFrame;
import io.github.sudoitir.artemisstudio.kernel.replica.StudioBus;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Fans a flushed batch of broker events out over SSE (ADR-0027, ADR-0148). The replica that wrote
 * the batch announces its seqs on the bus, in the transaction that stores them; every replica, that one included, loads the rows when the
 * announcement arrives and sends each as a frame on the data-bearing {@code events} topic, with its
 * {@code seq} as the SSE id. So a frame is delivered once per replica, and only the writer nudges the
 * resource-view signal topics the events imply are stale, through {@link TopicCoalescer} (D11); those
 * signals cross the bus themselves.
 */
@Component
@RequiredArgsConstructor
public class EventStreamPublisher implements BrokerEventPublisher {

    private final StudioBus bus;
    private final ApplicationEventPublisher frames;
    private final ObjectMapper mapper;
    private final TopicCoalescer coalescer;
    private final BrokerEventService events;
    private final BrokerEventRepository repository;

    /**
     * Runs inside the flush transaction: the announcement goes out when it commits. The signal topics
     * are nudged here too; a rolled-back flush costs at most one needless refetch.
     */
    @Override
    public void written(List<Long> seqs, List<BrokerEvent> batch) {
        if (seqs.isEmpty()) {
            return;
        }
        bus.publish(new BusEvents(seqs));
        for (BrokerEvent e : batch) {
            String derived = derivedTopicOf(e.type());
            if (derived != null) {
                coalescer.touch(e.clusterId(), derived);
            }
        }
    }

    /** Announced by some replica: hand each stored row to the local subscribers of its cluster. */
    @EventListener
    void on(BusEvents announced) {
        for (BrokerEventEntity e : repository.findBySeqInOrderBySeqAsc(announced.seqs())) {
            frames.publishEvent(new BusFrame(
                    e.getClusterId(),
                    EventsModule.TOPIC,
                    mapper.valueToTree(events.toView(e)),
                    Long.toString(e.getSeq())));
        }
    }

    static String derivedTopicOf(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            // CONSUMER_SLOW is the broker's own slow-consumer verdict (ADR-0044) and is
            // authoritative over Studio's derived rule. It carries _AMQ_ConsumerName, so
            // unlike the derived rule it attributes to a named consumer.
            case "CONSUMER_CREATED", "CONSUMER_CLOSED", "CONSUMER_SLOW" -> "consumers";
            case "SESSION_CREATED", "SESSION_CLOSED" -> "sessions";
            case "CONNECTION_CREATED", "CONNECTION_DESTROYED" -> "connections";
            case "BINDING_ADDED", "BINDING_REMOVED", "ADDRESS_ADDED", "ADDRESS_REMOVED" -> "queues";
            default -> null;
        };
    }
}
