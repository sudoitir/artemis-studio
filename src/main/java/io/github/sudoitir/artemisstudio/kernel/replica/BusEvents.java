package io.github.sudoitir.artemisstudio.kernel.replica;

import java.util.List;

/**
 * Broker events that were committed to {@code broker_event}: receivers load them by {@code seq}.
 * Published as a Spring event on every replica when it arrives.
 */
public record BusEvents(List<Long> seqs) implements BusMessage {}
