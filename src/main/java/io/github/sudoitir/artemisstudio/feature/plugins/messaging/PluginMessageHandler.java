package io.github.sudoitir.artemisstudio.feature.plugins.messaging;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/**
 * Implemented by one bean of a plugin that registers for messages (ADR-0111). Studio calls it on
 * its own threads, one message at a time per registration and node, with the plugin's classloader
 * set and the call counted so an unload waits for it.
 *
 * <p>For a {@link RegistrationMode#CONSUME} registration, only {@link Disposition#ACCEPT}
 * removes the message; {@link Disposition#REJECT}, an exception, or Studio stopping first leave it
 * for redelivery. A handler that blocks holds its registration's delivery on that node for as
 * long, and nothing else; bound its own waits.
 */
@PluginApi
public interface PluginMessageHandler {

    Disposition onMessage(PluginMessage message);
}
