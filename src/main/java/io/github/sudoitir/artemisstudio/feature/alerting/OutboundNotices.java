package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.UUID;

/**
 * Sends notices to notification channels. A plugin receives its own instance, bound to its id: every notice it
 * sends has the plugin as its source. A notice is delivered like an alert, with the same retries, signature
 * and delivery history.
 *
 * <p>Absent when the alerting feature is disabled, so a plugin injects an {@code ObjectProvider<OutboundNotices>}.
 */
@PluginApi
public interface OutboundNotices {

    /** The most notices one plugin may send per hour. */
    int MAX_PER_HOUR = 600;

    /** The most bytes one notice may take as JSON. */
    int MAX_BYTES = 8192;

    /** The most characters a dedupe key may have. */
    int MAX_DEDUPE_KEY = 200;

    /**
     * Queues {@code notice} for {@code channelId}, in the caller's transaction: it is sent only if that
     * transaction commits.
     *
     * <p>{@code dedupeKey} names the notice for this plugin: queueing a key the plugin already used is a silent
     * no-op, however long ago, and does not count toward {@link #MAX_PER_HOUR}. A relay that commits its own
     * cursor in a separate transaction derives the key from the event it relays (its id or sequence number), so a
     * crash between the two commits makes the retry harmless.
     *
     * @throws IllegalArgumentException when the channel does not exist, the notice is larger than
     *     {@link #MAX_BYTES}, the key is blank or longer than {@link #MAX_DEDUPE_KEY}, or the plugin already
     *     sent {@link #MAX_PER_HOUR} notices in the last hour
     */
    void enqueue(UUID channelId, NoticeMessage notice, String dedupeKey);
}
