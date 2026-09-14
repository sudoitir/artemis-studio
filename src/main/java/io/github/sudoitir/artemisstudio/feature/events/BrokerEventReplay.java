package io.github.sudoitir.artemisstudio.feature.events;

import io.github.sudoitir.artemisstudio.kernel.stream.EventReplay;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Replays the {@code events} topic from {@code broker_event.seq} (ADR-0027). */
@Component
@RequiredArgsConstructor
class BrokerEventReplay implements EventReplay {

    private final BrokerEventService events;

    @Override
    public String topic() {
        return EventsModule.TOPIC;
    }

    @Override
    public List<Replayed> since(UUID clusterId, long lastEventId, int cap) {
        return events.since(clusterId, lastEventId, cap).stream()
                .map(v -> new Replayed(Long.toString(v.seq()), v))
                .toList();
    }
}
