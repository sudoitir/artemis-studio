package io.github.sudoitir.artemisstudio.broker.routing;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * One divert as a running broker reports it, tagged with the node it was read
 * from — the routing view's row, on the pattern of {@code broker/QueueRow}.
 *
 * <p>Everything here comes from the divert MBean's own attributes, which are real
 * JSON types rather than Artemis' all-strings list envelope, so no per-field
 * coercion is needed.
 *
 * <p>There is deliberately no origin field. Artemis records nothing on a divert
 * saying whether it was declared in {@code broker.xml} or created over management,
 * and exposes no operation that returns configured-but-undeployed diverts, so any
 * such claim would be a guess (ADR-0065 D2). The one attribution the system can
 * make honestly is ownership, which comes from Studio's own records and is applied
 * a layer up.
 */
public record DivertRow(
        UUID nodeId,
        String nodeName,
        String uniqueName,
        String routingName,
        String address,
        String forwardingAddress,
        String filter,
        String routingType,
        String transformerClassName,
        boolean exclusive,
        boolean retroactiveResource) {

    /** Parse one divert MBean's attribute map. */
    public static DivertRow parse(JsonNode attributes, UUID nodeId, String nodeName) {
        return new DivertRow(
                nodeId,
                nodeName,
                text(attributes, "UniqueName"),
                text(attributes, "RoutingName"),
                text(attributes, "Address"),
                text(attributes, "ForwardingAddress"),
                text(attributes, "Filter"),
                text(attributes, "RoutingType"),
                text(attributes, "TransformerClassName"),
                flag(attributes, "Exclusive"),
                flag(attributes, "RetroactiveResource"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static boolean flag(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && !v.isNull() && v.asBoolean();
    }
}
