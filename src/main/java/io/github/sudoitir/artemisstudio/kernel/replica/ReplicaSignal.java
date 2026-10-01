package io.github.sudoitir.artemisstudio.kernel.replica;

/**
 * A cache somewhere changed, or something happened that every replica must know. {@code kind} says
 * what, {@code key} which one. Published as a Spring event on every replica, the sender included.
 */
public record ReplicaSignal(String kind, String key) implements BusMessage {}
