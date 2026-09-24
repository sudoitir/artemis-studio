package io.github.sudoitir.artemisstudio.feature.plugins.messaging;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/** What a {@link PluginMessageHandler} did with a message. */
@PluginApi
public enum Disposition {
    /** Done with it. A consumed message is acknowledged and leaves the queue. */
    ACCEPT,
    /**
     * Not done with it. A consumed message is redelivered, up to the broker's own
     * {@code max-delivery-attempts}; a tapped copy is discarded either way, since the original was
     * never taken from its queue.
     */
    REJECT
}
