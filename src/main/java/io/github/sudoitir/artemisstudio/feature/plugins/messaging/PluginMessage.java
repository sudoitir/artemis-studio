package io.github.sudoitir.artemisstudio.feature.plugins.messaging;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A message delivered to a plugin.
 *
 * @param registrationKey the registration it was delivered for
 * @param body the body's bytes; a text message's are its UTF-8 encoding, and a message with no
 *     readable body (map, stream, object) has none
 * @param text whether it was sent as a text message
 * @param headers the standard headers that are set: {@code messageId}, {@code correlationId},
 *     {@code type}, {@code priority}, {@code timestamp}, {@code expiration}, {@code durable},
 *     {@code replyTo}
 * @param properties the application properties, with their types; the broker's own
 *     ({@code _AMQ_*}) are left out
 * @param deliveryCount 1 on first delivery, more on a redelivery
 */
@PluginApi
public record PluginMessage(
        String registrationKey,
        UUID clusterId,
        UUID nodeId,
        String queue,
        byte[] body,
        boolean text,
        Map<String, Object> headers,
        Map<String, Object> properties,
        int deliveryCount) {

    /** The body decoded as UTF-8. */
    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** Equal when every component is, the body by content. */
    @Override
    public boolean equals(Object other) {
        return other
                        instanceof
                        PluginMessage(
                                var otherRegistrationKey,
                                var otherClusterId,
                                var otherNodeId,
                                var otherQueue,
                                var otherBody,
                                var otherText,
                                var otherHeaders,
                                var otherProperties,
                                var otherDeliveryCount)
                && Objects.equals(registrationKey, otherRegistrationKey)
                && Objects.equals(clusterId, otherClusterId)
                && Objects.equals(nodeId, otherNodeId)
                && Objects.equals(queue, otherQueue)
                && Arrays.equals(body, otherBody)
                && text == otherText
                && Objects.equals(headers, otherHeaders)
                && Objects.equals(properties, otherProperties)
                && deliveryCount == otherDeliveryCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                registrationKey,
                clusterId,
                nodeId,
                queue,
                Arrays.hashCode(body),
                text,
                headers,
                properties,
                deliveryCount);
    }

    /** The body is reported by length only, so a message's content never lands in a log line. */
    @Override
    public String toString() {
        return "PluginMessage[registrationKey=" + registrationKey + ", clusterId=" + clusterId + ", nodeId=" + nodeId
                + ", queue=" + queue + ", body=" + (body == null ? "null" : body.length + " bytes") + ", text=" + text
                + ", headers=" + headers + ", properties=" + properties + ", deliveryCount=" + deliveryCount + "]";
    }
}
