package io.github.sudoitir.artemisstudio.feature.setupreview;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaRequest;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaResponse;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads one node for the review in a single batched Jolokia POST (non-negotiable #1, ADR-0106),
 * taken through the node's rate limiter like every other call:
 *
 * <ol>
 *   <li>every attribute of the broker MBean — HA policy, clustering, persistence, security, disk
 *       limits, connectors and acceptors;
 *   <li>the effective address settings for {@code #};
 *   <li>every attribute of every cluster connection, by pattern.
 * </ol>
 *
 * <p>Parts fail independently. A broker with no cluster connection answers the pattern read with
 * 404, which is "none", not an error; anything else leaves that part null with its reason, and the
 * rules that need it report themselves as not assessed. Never a write.
 */
@Component
@RequiredArgsConstructor
public class SetupReader {

    private final ObjectMapper mapper;

    public NodeRead read(JolokiaBrokerClient client, ClusterNode node) {
        boolean live = Boolean.TRUE.equals(node.getActive());
        try {
            String broker = client.resolveBrokerObjectName();
            List<JolokiaResponse> responses = client.batch(List.of(
                    JolokiaRequest.readAll(broker),
                    JolokiaRequest.exec(broker, "getAddressSettingsAsJSON(java.lang.String)", "#"),
                    JolokiaRequest.readAll(broker + ",component=cluster-connections,name=*")));
            if (responses.size() != 3) {
                return NodeRead.unreadable(
                        node.getId(),
                        node.getName(),
                        node.getArtemisNodeId(),
                        live,
                        true,
                        "The node answered " + responses.size() + " of 3 batched requests.");
            }
            JolokiaResponse attributes = responses.get(0);
            if (!attributes.ok()) {
                return NodeRead.unreadable(
                        node.getId(),
                        node.getName(),
                        node.getArtemisNodeId(),
                        live,
                        true,
                        "The broker MBean could not be read: " + reason(attributes));
            }
            JsonNode brokerAttributes = attributes.value();

            JsonNode settings = null;
            String settingsError = null;
            JsonNode settingsResponse = responses.get(1).ok() ? client.parsed(responses.get(1)) : null;
            if (settingsResponse != null && settingsResponse.isObject()) {
                settings = settingsResponse;
            } else {
                settingsError = "The default address settings could not be read: " + reason(responses.get(1));
            }

            Map<String, JsonNode> connections = null;
            String connectionsError = null;
            JolokiaResponse cc = responses.get(2);
            if (cc.ok() && cc.value() != null && cc.value().isObject()) {
                connections = new LinkedHashMap<>();
                for (var entry : cc.value().properties()) {
                    connections.put(name(entry.getKey()), entry.getValue());
                }
            } else if (cc.status() == 404) {
                connections = Map.of();
            } else {
                connectionsError = "The cluster connections could not be read: " + reason(cc);
            }

            String artemisNodeId = node.getArtemisNodeId();
            JsonNode reported = brokerAttributes.get("NodeID");
            if (reported != null && !reported.isNull() && !reported.asString().isBlank()) {
                artemisNodeId = reported.asString();
            }
            JsonNode active = brokerAttributes.get("Active");
            if (active != null && active.isBoolean()) {
                live = active.asBoolean();
            }
            return new NodeRead(
                    node.getId(),
                    node.getName(),
                    artemisNodeId,
                    live,
                    true,
                    null,
                    brokerAttributes,
                    settings,
                    settingsError,
                    connections,
                    connectionsError);
        } catch (BrokerConnectionException e) {
            return NodeRead.unreadable(
                    node.getId(),
                    node.getName(),
                    node.getArtemisNodeId(),
                    live,
                    true,
                    e.getMessage() != null ? e.getMessage() : e.kind().defaultMessage());
        } catch (RuntimeException e) {
            return NodeRead.unreadable(
                    node.getId(),
                    node.getName(),
                    node.getArtemisNodeId(),
                    live,
                    true,
                    "The node's answer could not be read: " + e.getMessage());
        }
    }

    /** {@code ...,component=cluster-connections,name="studio-dev"} → {@code studio-dev}. */
    static String name(String objectName) {
        int at = objectName.lastIndexOf("name=");
        if (at < 0) {
            return objectName;
        }
        String name = objectName.substring(at + "name=".length());
        int comma = name.indexOf(',');
        if (comma >= 0) {
            name = name.substring(0, comma);
        }
        return name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")
                ? name.substring(1, name.length() - 1)
                : name;
    }

    private static String reason(JolokiaResponse response) {
        return response.error() != null ? response.error() : "status " + response.status();
    }
}
