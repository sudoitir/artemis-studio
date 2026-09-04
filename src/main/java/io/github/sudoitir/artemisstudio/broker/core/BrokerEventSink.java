package io.github.sudoitir.artemisstudio.broker.core;

/**
 * Where {@link CoreEventClient} hands normalised notifications. Slice 1 ships a
 * logging no-op; slice 2's {@code BrokerEventWriter} is the real implementation
 * (buffered batch insert). Keeping this an interface lets each slice ship on its
 * own.
 */
public interface BrokerEventSink {

    void accept(BrokerEvent event);
}
