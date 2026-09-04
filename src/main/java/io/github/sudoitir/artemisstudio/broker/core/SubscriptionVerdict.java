package io.github.sudoitir.artemisstudio.broker.core;

import java.time.Instant;

/**
 * The cached outcome of a cluster's Core subscription, read by
 * {@code CapabilityProbe} without opening a connection (ADR-0026, D5).
 */
public sealed interface SubscriptionVerdict
        permits SubscriptionVerdict.Connected, SubscriptionVerdict.Failed, SubscriptionVerdict.NotAttempted {

    /** Subscribed on at least one node. */
    record Connected(int nodeCount, Instant since) implements SubscriptionVerdict {}

    /** Every attempt failed; {@code kind} carries the most actionable reason. */
    record Failed(CoreEventClient.Kind kind, String reason) implements SubscriptionVerdict {}

    /** No live node has been probed yet — the first scrape cycle has not completed. */
    record NotAttempted() implements SubscriptionVerdict {}

    default boolean isConnected() {
        return this instanceof Connected;
    }
}
