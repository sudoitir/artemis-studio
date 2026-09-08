package io.github.sudoitir.artemisstudio.broker.routing;

import io.github.sudoitir.artemisstudio.broker.BrokerMBeans;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.JolokiaRequest;
import io.github.sudoitir.artemisstudio.broker.JolokiaResponse;
import io.github.sudoitir.artemisstudio.broker.ManagementRefusal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Divert and bridge management over Jolokia, on the pattern of
 * {@code QueueLifecycleOperations}: one {@code exec} per mutation, refusals the
 * broker explained surfacing as {@link ManagementRefusal}, everything else as a
 * connection failure.
 *
 * <p>Reading is two round trips per node and cannot be fewer. A divert is a
 * sub-component of its <em>source address</em>, so its object name contains an
 * address the broker never tells us: {@code DivertNames} returns bare names. The
 * first call is therefore a JMX search for the divert MBeans, and the second is a
 * single batched read of all of them. Verified against Artemis 2.56.0 — the search
 * for {@code component=addresses,address=*,subcomponent=diverts,divert=*} returns
 * exactly the deployed diverts, with their addresses in the object names.
 *
 * <p>Mutations use the single-String JSON {@code DivertConfiguration} overloads.
 * The positional {@code createDivert} arms take a nullable filter and transformer,
 * and {@code JolokiaRequest.exec} cannot carry a null argument; the JSON arm takes
 * one argument, omits what is unset, and is the current surface besides.
 */
@Component
public class DivertOperations {

    private final ObjectMapper mapper;

    public DivertOperations(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ---- reads -----------------------------------------------------------

    /** Every divert deployed on this node. An empty list is a real answer, not a failure. */
    public List<DivertRow> listDiverts(JolokiaBrokerClient client, UUID nodeId, String nodeName) {
        String broker = client.resolveBrokerObjectName();
        List<String> objectNames = client.search(BrokerMBeans.divertsPattern(broker));
        return readAll(client, objectNames, (attrs) -> DivertRow.parse(attrs, nodeId, nodeName));
    }

    /** Every bridge deployed on this node, with its started and connected state. */
    public List<BridgeRow> listBridges(JolokiaBrokerClient client, UUID nodeId, String nodeName) {
        String broker = client.resolveBrokerObjectName();
        List<String> objectNames = client.search(BrokerMBeans.bridgesPattern(broker));
        return readAll(client, objectNames, (attrs) -> BridgeRow.parse(attrs, nodeId, nodeName));
    }

    /**
     * One batched POST reading every named MBean in full. A single MBean that has
     * disappeared between the search and the read contributes nothing rather than
     * failing the page — routing changes under us, and a half-listed view is more
     * useful than an error.
     */
    private <T> List<T> readAll(
            JolokiaBrokerClient client,
            List<String> objectNames,
            java.util.function.Function<tools.jackson.databind.JsonNode, T> parse) {
        if (objectNames.isEmpty()) {
            return List.of();
        }
        List<JolokiaRequest> requests =
                objectNames.stream().map(JolokiaRequest::readAll).toList();
        List<JolokiaResponse> responses = client.batch(requests);
        List<T> rows = new ArrayList<>();
        for (JolokiaResponse response : responses) {
            if (!response.ok()) {
                continue;
            }
            tools.jackson.databind.JsonNode value = response.value();
            if (value != null && value.isObject()) {
                rows.add(parse.apply(value));
            }
        }
        return List.copyOf(rows);
    }

    // ---- mutations -------------------------------------------------------

    /**
     * Create a divert from a {@code DivertConfiguration} document. A divert that
     * already exists raises {@link ManagementRefusal.Kind#ALREADY}, which the caller
     * reports as "already in the requested state" rather than as a failure.
     */
    public void createDivert(JolokiaBrokerClient client, String brokerMbean, Map<String, Object> config) {
        JolokiaResponse res = client.single(
                JolokiaRequest.exec(brokerMbean, "createDivert(java.lang.String)", mapper.writeValueAsString(config)));
        require(res, "createDivert");
    }

    /** Destroy a divert. One that is already gone raises {@link ManagementRefusal.Kind#ALREADY}. */
    public void destroyDivert(JolokiaBrokerClient client, String brokerMbean, String name) {
        require(
                client.single(JolokiaRequest.exec(brokerMbean, "destroyDivert(java.lang.String)", name)),
                "destroyDivert");
    }

    /**
     * A {@code DivertConfiguration} document from the fields Studio exposes. Keys are
     * the hyphenated configuration names Artemis' JSON arm expects; anything unset is
     * omitted so the broker's own default stands rather than being overwritten with a
     * null.
     */
    public static Map<String, Object> divertConfig(
            String name,
            String routingName,
            String address,
            String forwardingAddress,
            boolean exclusive,
            String filter,
            String routingType) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", name);
        config.put("routing-name", routingName == null || routingName.isBlank() ? name : routingName);
        config.put("address", address);
        config.put("forwarding-address", forwardingAddress);
        config.put("exclusive", exclusive);
        if (filter != null && !filter.isBlank()) {
            config.put("filter-string", filter);
        }
        if (routingType != null && !routingType.isBlank()) {
            config.put("routing-type", routingType.toUpperCase());
        }
        return config;
    }

    private static void require(JolokiaResponse res, String operation) {
        ManagementRefusal.require(res, operation);
    }
}
