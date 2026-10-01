package io.github.sudoitir.artemisstudio.feature.events;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerEvent;
import java.util.List;

/**
 * Called inside the transaction of a flush that inserted a batch of broker events, with the seqs the
 * insert produced, in the batch's order. Only transactional work belongs here (a bus announcement is
 * sent at commit), or work that is harmless if the flush rolls back. {@link BrokerEventWriter} skips
 * it when there is no implementation, so the history feature is complete without the stream.
 */
public interface BrokerEventPublisher {

    void written(List<Long> seqs, List<BrokerEvent> batch);
}
