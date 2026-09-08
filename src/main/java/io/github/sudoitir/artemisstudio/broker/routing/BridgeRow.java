package io.github.sudoitir.artemisstudio.broker.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * One bridge as a running broker reports it, tagged with the node it was read from.
 *
 * <p>{@code started} and {@code connected} are separate facts and both are carried:
 * a bridge can be started and unable to reach its target, which is the state an
 * operator asking "is this bridge actually running" needs to be able to see.
 *
 * <p>Bridges are read-only in this product, permanently — see the routing spec.
 */
public record BridgeRow(
        UUID nodeId,
        String nodeName,
        String name,
        String queueName,
        String forwardingAddress,
        String filterString,
        String discoveryGroupName,
        String transformerClassName,
        List<String> staticConnectors,
        long messagesAcknowledged,
        long messagesPendingAcknowledgement,
        boolean started,
        boolean connected,
        boolean useDuplicateDetection,
        boolean highlyAvailable) {

    /** Parse one bridge MBean's attribute map. */
    public static BridgeRow parse(JsonNode attributes, UUID nodeId, String nodeName) {
        return new BridgeRow(
                nodeId,
                nodeName,
                text(attributes, "Name"),
                text(attributes, "QueueName"),
                text(attributes, "ForwardingAddress"),
                text(attributes, "FilterString"),
                text(attributes, "DiscoveryGroupName"),
                text(attributes, "TransformerClassName"),
                strings(attributes, "StaticConnectors"),
                num(attributes, "MessagesAcknowledged"),
                num(attributes, "MessagesPendingAcknowledgement"),
                flag(attributes, "Started"),
                flag(attributes, "Connected"),
                flag(attributes, "UseDuplicateDetection"),
                flag(attributes, "HA"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static List<String> strings(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || !v.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        v.forEach(e -> out.add(e.asString()));
        return List.copyOf(out);
    }

    private static long num(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? 0L : v.asLong();
    }

    private static boolean flag(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && !v.isNull() && v.asBoolean();
    }
}
