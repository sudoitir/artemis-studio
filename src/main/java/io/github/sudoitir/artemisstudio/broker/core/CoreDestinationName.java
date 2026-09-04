package io.github.sudoitir.artemisstudio.broker.core;

/**
 * Extracts the routing name from a Core JMS destination's {@code toString()}
 * (e.g. {@code ActiveMQTemporaryQueue[2324a578-...]} → {@code 2324a578-...}),
 * confirmed against {@code _AMQ_RoutingName} in the Phase 5 spike
 * (docs/broker-management-notes.md §13, answer 6) — this is the temp-queue join
 * key between a browsed message's {@code JMSReplyTo} and a binding notification.
 */
public final class CoreDestinationName {

    private CoreDestinationName() {}

    public static String extract(String destinationToString) {
        if (destinationToString == null) {
            return null;
        }
        int open = destinationToString.indexOf('[');
        int close = destinationToString.lastIndexOf(']');
        if (open >= 0 && close > open) {
            return destinationToString.substring(open + 1, close);
        }
        return destinationToString;
    }
}
