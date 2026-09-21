package io.github.sudoitir.artemisstudio.feature.queues;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerListOps;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerMBeans;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaRequest;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaResponse;
import io.github.sudoitir.artemisstudio.platform.broker.ManagementRefusal;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome.NodeStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Divert management over Jolokia, on the pattern of
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

    /**
     * The name prefix Studio reserves for the diverts that serve message capture (ADR-0079).
     * A divert under it is Studio's by construction, and is never created or deleted as an
     * operator's divert.
     */
    public static final String CAPTURE_PREFIX = "artemis-studio.capture.";

    private final ObjectMapper mapper;

    public DivertOperations(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ---- reads -----------------------------------------------------------

    /** Every divert deployed on this node. An empty list is a real answer, not a failure. */
    public List<DivertRow> listDiverts(JolokiaBrokerClient client, UUID nodeId, String nodeName) {
        String broker = client.resolveBrokerObjectName();
        return BrokerListOps.readAll(
                client, client.search(BrokerMBeans.divertsPattern(broker)), a -> DivertRow.parse(a, nodeId, nodeName));
    }

    /** One divert by name on this node, or empty when it is not deployed there. */
    public Optional<DivertRow> find(JolokiaBrokerClient client, String name) {
        return listDiverts(client, null, null).stream()
                .filter(d -> name.equals(d.uniqueName()))
                .findFirst();
    }

    /** Whether the address exists on this node, or would be created the first time something is sent to it. */
    public boolean addressAvailable(JolokiaBrokerClient client, String brokerMbean, String address) {
        if (!client.search(BrokerMBeans.address(brokerMbean, address)).isEmpty()) {
            return true;
        }
        JsonNode settings = client.execOnBrokerParsed("getAddressSettingsAsJSON(java.lang.String)", address);
        return settings != null && settings.path("autoCreateAddresses").asBoolean(false);
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

    /**
     * Create a divert and report what the broker actually deployed. Artemis answers 200 and
     * only logs a warning when it declines to deploy one, so the status code proves nothing:
     * the node's divert list is read before and after.
     *
     * @return {@code ALREADY} when an identical divert was there before, {@code APPLIED} when
     *     it is now deployed as asked
     * @throws ManagementRefusal naming the differing fields, or saying it was not deployed
     */
    public NodeStatus createVerified(JolokiaBrokerClient client, String brokerMbean, Map<String, Object> config) {
        String name = String.valueOf(config.get("name"));
        Optional<DivertRow> before = find(client, name);
        if (before.isPresent()) {
            requireSame(before.get(), config);
            return NodeStatus.ALREADY;
        }
        try {
            createDivert(client, brokerMbean, config);
        } catch (ManagementRefusal e) {
            if (e.kind() != ManagementRefusal.Kind.ALREADY) {
                throw e;
            }
        }
        DivertRow after = find(client, name)
                .orElseThrow(() -> new ManagementRefusal(
                        ManagementRefusal.Kind.ARGUMENT,
                        "The broker accepted divert '" + name + "' but did not deploy it. Its log says why; the usual"
                                + " cause is an existing binding with the same routing name."));
        requireSame(after, config);
        return NodeStatus.APPLIED;
    }

    private static void requireSame(DivertRow row, Map<String, Object> config) {
        List<String> differing = differences(row, config);
        if (!differing.isEmpty()) {
            throw new ManagementRefusal(
                    ManagementRefusal.Kind.ARGUMENT,
                    "A divert named '" + row.uniqueName() + "' exists here with a different "
                            + String.join(", ", differing) + ". Delete it first; a divert is never changed in place.");
        }
    }

    /** The configuration keys on which a deployed divert differs from the requested one. */
    static List<String> differences(DivertRow row, Map<String, Object> config) {
        List<String> differing = new ArrayList<>();
        compare(differing, "address", row.address(), config.get("address"));
        compare(differing, "forwarding-address", row.forwardingAddress(), config.get("forwarding-address"));
        compare(differing, "routing-name", row.routingName(), config.get("routing-name"));
        compare(differing, "filter-string", row.filter(), config.get("filter-string"));
        compare(differing, "exclusive", String.valueOf(row.exclusive()), config.get("exclusive"));
        // Unset means the broker's default, which is not compared rather than guessed.
        if (config.containsKey("routing-type")) {
            compare(differing, "routing-type", row.routingType(), config.get("routing-type"));
        }
        BridgeOperations.transformerDifferences(
                differing, config, row.transformerClassName(), row.transformerProperties());
        return differing;
    }

    private static void compare(List<String> differing, String key, String actual, Object wanted) {
        String a = actual == null || actual.isBlank() ? null : actual;
        String w = wanted == null || String.valueOf(wanted).isBlank() ? null : String.valueOf(wanted);
        if (!Objects.equals(a, w)) {
            differing.add(key);
        }
    }

    /**
     * The cycle a new divert would close, as {@code A → B → A}, or null. Built from the
     * diverts already on the node, excluding any with the new divert's name.
     */
    static String cycle(List<DivertRow> existing, String name, String address, String forwardingAddress) {
        Map<String, List<String>> edges = new HashMap<>();
        for (DivertRow d : existing) {
            if (d.address() != null && d.forwardingAddress() != null && !Objects.equals(d.uniqueName(), name)) {
                edges.computeIfAbsent(d.address(), k -> new ArrayList<>()).add(d.forwardingAddress());
            }
        }
        List<String> path = pathTo(edges, forwardingAddress, address, new HashSet<>());
        if (path == null) {
            return null;
        }
        List<String> cycle = new ArrayList<>();
        cycle.add(address);
        cycle.addAll(path);
        return String.join(" → ", cycle);
    }

    private static List<String> pathTo(Map<String, List<String>> edges, String from, String to, Set<String> seen) {
        if (from.equals(to)) {
            return new ArrayList<>(List.of(from));
        }
        if (!seen.add(from)) {
            return null;
        }
        for (String next : edges.getOrDefault(from, List.of())) {
            List<String> rest = pathTo(edges, next, to, seen);
            if (rest != null) {
                rest.addFirst(from);
                return rest;
            }
        }
        return null;
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
