package io.github.sudoitir.artemisstudio.feature.events;

import io.github.sudoitir.artemisstudio.feature.events.internal.persistence.BrokerEventEntity;
import java.util.List;

/**
 * Slice-3 hook: after the transaction of a flush that persisted a batch of broker events
 * has committed, exactly those rows are handed here to fan out over SSE. Slice 2 ships no
 * implementation — {@link BrokerEventWriter} skips it when absent, so the history feature
 * is complete without the stream.
 */
public interface BrokerEventPublisher {

    void published(List<BrokerEventEntity> events);
}
