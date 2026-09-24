package io.github.sudoitir.artemisstudio.feature.setupreview;

import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * What one batched read of one node returned (ADR-0106), plus what Studio already knows about the
 * node. A part the broker did not answer is null with its reason, so the rules that need it say
 * "not assessed" instead of guessing.
 *
 * @param artemisNodeId the broker's NodeID, shared by a primary and its backup; null before first read
 * @param live the node reported itself active at its last scrape
 * @param manageable Studio has a Jolokia URL for it
 * @param unavailableReason why the node could not be read at all, or null
 * @param broker the broker MBean's attributes
 * @param defaultAddressSettings the effective settings for {@code #}
 * @param clusterConnections cluster-connection name to its attributes; empty when it has none
 */
public record NodeRead(
        UUID nodeId,
        String nodeName,
        String artemisNodeId,
        boolean live,
        boolean manageable,
        String unavailableReason,
        JsonNode broker,
        JsonNode defaultAddressSettings,
        String addressSettingsError,
        Map<String, JsonNode> clusterConnections,
        String clusterConnectionsError) {

    public boolean readable() {
        return manageable && unavailableReason == null && broker != null;
    }

    public String subject() {
        return "node:" + nodeId;
    }

    public static NodeRead unreadable(
            UUID nodeId, String nodeName, String artemisNodeId, boolean live, boolean manageable, String reason) {
        return new NodeRead(nodeId, nodeName, artemisNodeId, live, manageable, reason, null, null, null, null, null);
    }
}
