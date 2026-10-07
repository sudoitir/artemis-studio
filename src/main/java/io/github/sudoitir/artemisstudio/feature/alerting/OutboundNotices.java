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

    /**
     * Queues {@code notice} for {@code channelId}, in the caller's transaction: it is sent only if that
     * transaction commits.
     *
     * @throws IllegalArgumentException when the channel does not exist, the notice is larger than
     *     {@link #MAX_BYTES}, or the plugin already sent {@link #MAX_PER_HOUR} notices in the last hour
     */
    void enqueue(UUID channelId, NoticeMessage notice);
}
