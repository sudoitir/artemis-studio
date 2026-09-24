package io.github.sudoitir.artemisstudio.feature.plugins.messaging;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.Map;
import java.util.UUID;

/**
 * A message a plugin sends.
 *
 * @param address an address, or a fully qualified queue name ({@code address::queue})
 * @param body the body; sent as a text message when {@code text}, else as bytes
 * @param headers string properties set on the message, as Studio's own send sets them
 * @param properties typed properties (string, boolean, int, long, double)
 * @param actingUserId the Studio user sending it, who needs {@code message:send} on the cluster
 */
@PluginApi
public record OutboundMessage(
        UUID clusterId,
        String address,
        byte[] body,
        boolean text,
        Map<String, String> headers,
        Map<String, Object> properties,
        boolean durable,
        UUID actingUserId) {}
