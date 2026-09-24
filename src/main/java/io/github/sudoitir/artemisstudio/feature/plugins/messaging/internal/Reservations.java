package io.github.sudoitir.artemisstudio.feature.plugins.messaging.internal;

import java.util.List;
import java.util.Locale;

/**
 * The queues and addresses no plugin may register on or send to: Studio's own (capture, plugin
 * taps, transfer staging and anything else under its prefix) and the broker's management and
 * notification addresses.
 */
public final class Reservations {

    private static final List<String> PREFIXES =
            List.of("artemis-studio.", "studio.transfer.", "activemq.management", "activemq.notifications");

    private Reservations() {}

    /** Why the name is reserved, or {@code null} when a plugin may use it. */
    public static String reason(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : PREFIXES) {
            if (lower.startsWith(prefix)) {
                return "'" + name + "' is under " + prefix + ", which is reserved for Studio or the broker itself.";
            }
        }
        return null;
    }
}
