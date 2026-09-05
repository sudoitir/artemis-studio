package io.github.sudoitir.artemisstudio.sse;

import io.github.sudoitir.artemisstudio.persist.BrokerEventEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerEventPublisher;
import io.github.sudoitir.artemisstudio.service.BrokerEventService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Fans a flushed batch of broker events out over SSE (ADR-0027). Each event goes
 * on the data-bearing {@code events} topic with its {@code seq} as the SSE id;
 * the resource-view signal topics it implies are stale are then nudged through
 * {@link TopicCoalescer} (D11).
 */
@Component
@RequiredArgsConstructor
public class EventStreamPublisher implements BrokerEventPublisher {

    private final SseHub hub;
    private final TopicCoalescer coalescer;
    private final BrokerEventService events;

    @Override
    public void published(List<BrokerEventEntity> batch) {
        for (BrokerEventEntity e : batch) {
            hub.publish(e.getClusterId(), "events", events.toView(e), Long.toString(e.getSeq()));
            String derived = derivedTopicOf(e.getType());
            if (derived != null) {
                coalescer.touch(e.getClusterId(), derived);
            }
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
