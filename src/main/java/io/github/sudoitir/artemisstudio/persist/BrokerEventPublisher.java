package io.github.sudoitir.artemisstudio.persist;

import java.util.List;

/**
 * Slice-3 hook: after a flush persists a batch of broker events, they are handed
 * here to fan out over SSE. Slice 2 ships no implementation — {@link BrokerEventWriter}
 * skips it when absent, so the history feature is complete without the stream.
 */
public interface BrokerEventPublisher {

    void published(List<BrokerEventEntity> events);
}
