package io.github.sudoitir.artemisstudio.broker.brokerconfig;

import io.github.sudoitir.artemisstudio.broker.BrokerMBeans;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.JolokiaRequest;
import io.github.sudoitir.artemisstudio.broker.JolokiaResponse;
import io.github.sudoitir.artemisstudio.broker.ManagementRefusal;
import io.github.sudoitir.artemisstudio.broker.QueueLifecycleOperations;
import io.github.sudoitir.artemisstudio.broker.routing.DivertOperations;
import io.github.sudoitir.artemisstudio.broker.routing.DivertRow;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.AddressMatch;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.ObservedNodeConfig;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.ObservedNodeConfig.AddressUsage;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.PermissionType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The management calls behind a configuration apply and its verification (ADR-0067).
 * One {@code exec} per write, and the observed read of a node in <em>two</em> batched
 * POSTs — the second depends on names the first returns — never a request per
 * declared item.
 *
 * <p>Every signature here was measured against the running broker rather than the
 * classpath ({@code docs/broker-management-notes.md} §15): the JSON arm of
 * {@code addAddressSettings}, the 13-String arm of {@code addSecuritySettings} (the
 * single-JSON overload is not registered on the MBean), and the JSON arm of
 * {@code createDivert}. Queue and address writes reuse
 * {@link QueueLifecycleOperations}; divert writes reuse {@link DivertOperations}.
 */
@Component
public class BrokerConfigOperations {

    static final String ADD_ADDRESS_SETTINGS = "addAddressSettings(java.lang.String,java.lang.String)";
    static final String REMOVE_ADDRESS_SETTINGS = "removeAddressSettings(java.lang.String)";
    static final String ADD_SECURITY_SETTINGS = "addSecuritySettings(java.lang.String,java.lang.String,"
            + "java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,"
            + "java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String)";
    static final String REMOVE_SECURITY_SETTINGS = "removeSecuritySettings(java.lang.String)";
    static final String GET_ADDRESS_SETTINGS = "getAddressSettingsAsJSON(java.lang.String)";
    static final String GET_ROLES = "getRolesAsJSON(java.lang.String)";
    static final String DEFAULT_MATCH = "#";

    /** Address MBeans read per node for usage and routing types, so a wide match does not become a wide POST. */
    static final int ADDRESS_READ_CAP = 200;

    private final ObjectMapper mapper;
    private final QueueLifecycleOperations queueOps;
    private final DivertOperations divertOps;

    public BrokerConfigOperations(ObjectMapper mapper, QueueLifecycleOperations queueOps, DivertOperations divertOps) {
        this.mapper = mapper;
        this.queueOps = queueOps;
        this.divertOps = divertOps;
    }

    // ---- writes ----------------------------------------------------------

    /** Replace the address setting for a match with exactly these keys (the broker replaces, §15 M2). */
    public void addAddressSettings(
            JolokiaBrokerClient client, String brokerMbean, String match, Map<String, Object> values) {
        JolokiaResponse res = client.single(
                JolokiaRequest.exec(brokerMbean, ADD_ADDRESS_SETTINGS, match, mapper.writeValueAsString(values)));
        ManagementRefusal.require(res, "addAddressSettings");
    }

    /** Remove the address setting for a match. Absent is a success (§15 M4). */
    public void removeAddressSettings(JolokiaBrokerClient client, String brokerMbean, String match) {
        ManagementRefusal.require(
                client.single(JolokiaRequest.exec(brokerMbean, REMOVE_ADDRESS_SETTINGS, match)),
                "removeAddressSettings");
    }

    /** Replace the role set for a match. Roles are comma-joined per permission type, {@code ""} for none. */
    public void addSecuritySettings(
            JolokiaBrokerClient client, String brokerMbean, String match, Map<PermissionType, Set<String>> roles) {
        Object[] args = new Object[PermissionType.values().length + 1];
        args[0] = match;
        for (PermissionType t : PermissionType.values()) {
            Set<String> r = roles.getOrDefault(t, Set.of());
            args[t.ordinal() + 1] = String.join(",", new TreeSet<>(r));
        }
        ManagementRefusal.require(
                client.single(JolokiaRequest.exec(brokerMbean, ADD_SECURITY_SETTINGS, args)), "addSecuritySettings");
    }

    public void removeSecuritySettings(JolokiaBrokerClient client, String brokerMbean, String match) {
        ManagementRefusal.require(
                client.single(JolokiaRequest.exec(brokerMbean, REMOVE_SECURITY_SETTINGS, match)),
                "removeSecuritySettings");
    }

    public void createDivert(JolokiaBrokerClient client, String brokerMbean, Map<String, Object> config) {
        divertOps.createDivert(client, brokerMbean, config);
    }

    public void destroyDivert(JolokiaBrokerClient client, String brokerMbean, String name) {
        divertOps.destroyDivert(client, brokerMbean, name);
    }

    public void createAddress(
            JolokiaBrokerClient client, String brokerMbean, String address, Set<String> routingTypes) {
        queueOps.createAddress(client, brokerMbean, address, String.join(",", new TreeSet<>(routingTypes)));
    }

    public void createQueue(JolokiaBrokerClient client, String brokerMbean, Map<String, Object> config) {
        queueOps.createQueue(client, brokerMbean, config);
    }

    // ---- the observed read ------------------------------------------------

    /** What to read on a node: the declared and owned items, so nothing per undeclared item is fetched. */
    public record ReadScope(
            Set<String> addressSettingMatches,
            Set<String> securitySettingMatches,
            Set<String> divertNames,
            Map<String, BrokerConfigDocument.AddressDecl> addresses,
            Set<String> divertEndpoints) {

        public static ReadScope of(
                BrokerConfigDocument doc, Set<String> ownedSettingMatches, Set<String> ownedSecurityMatches) {
            Set<String> settings = new LinkedHashSet<>(ownedSettingMatches);
            doc.addressSettings().forEach(s -> settings.add(s.match()));
            Set<String> security = new LinkedHashSet<>(ownedSecurityMatches);
            doc.securitySettings().forEach(s -> security.add(s.match()));
            Set<String> diverts = new LinkedHashSet<>();
            doc.diverts().forEach(d -> diverts.add(d.name()));
            Map<String, BrokerConfigDocument.AddressDecl> addresses = new LinkedHashMap<>();
            doc.addresses().forEach(a -> addresses.put(a.name(), a));
            Set<String> endpoints = new LinkedHashSet<>();
            for (BrokerConfigDocument.DivertDecl d : doc.diverts()) {
                if (d.address() != null) {
                    endpoints.add(d.address());
                }
                if (d.forwardingAddress() != null) {
                    endpoints.add(d.forwardingAddress());
                }
            }
            return new ReadScope(settings, security, diverts, addresses, endpoints);
        }
    }

    /**
     * Read one live node. Two batched POSTs: the first fetches the broker's names,
     * every scoped address setting and role set, and searches the divert MBeans; the
     * second reads the divert, address and queue MBeans those names identify.
     *
     * @throws io.github.sudoitir.artemisstudio.broker.BrokerConnectionException when
     *     the node cannot be read at all — the caller marks it unreachable
     */
    public ObservedNodeConfig read(JolokiaBrokerClient client, UUID nodeId, String nodeName, ReadScope scope) {
        String broker = client.resolveBrokerObjectName();

        List<String> settingMatches = new ArrayList<>();
        settingMatches.add(DEFAULT_MATCH);
        scope.addressSettingMatches().stream()
                .filter(m -> !m.equals(DEFAULT_MATCH))
                .forEach(settingMatches::add);
        List<String> securityMatches = new ArrayList<>();
        securityMatches.add(DEFAULT_MATCH);
        scope.securitySettingMatches().stream()
                .filter(m -> !m.equals(DEFAULT_MATCH))
                .forEach(securityMatches::add);

        List<JolokiaRequest> first = new ArrayList<>();
        first.add(JolokiaRequest.read(broker, "Active", "AddressNames", "QueueNames", "DivertNames"));
        for (String m : settingMatches) {
            first.add(JolokiaRequest.exec(broker, GET_ADDRESS_SETTINGS, m));
        }
        for (String m : securityMatches) {
            first.add(JolokiaRequest.exec(broker, GET_ROLES, m));
        }
        first.add(JolokiaRequest.search(BrokerMBeans.divertsPattern(broker)));
        List<JolokiaResponse> r1 = client.batch(first);
        requireCount(r1, first);

        JsonNode head = client.parsed(r1.getFirst());
        boolean live = head.path("Active").asBoolean(false);
        Set<String> addressNames = names(head.get("AddressNames"));
        Set<String> queueNames = names(head.get("QueueNames"));

        Map<String, Map<String, Object>> addressSettings = new LinkedHashMap<>();
        int i = 1;
        for (String m : settingMatches) {
            JolokiaResponse res = r1.get(i++);
            if (res.ok()) {
                JsonNode json = client.parsed(res);
                if (json != null && json.isObject()) {
                    addressSettings.put(
                            m,
                            mapper.convertValue(
                                    json, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}));
                }
            }
        }
        Map<String, Map<PermissionType, Set<String>>> securitySettings = new LinkedHashMap<>();
        for (String m : securityMatches) {
            JolokiaResponse res = r1.get(i++);
            if (res.ok()) {
                securitySettings.put(m, roles(client.parsed(res)));
            }
        }
        List<String> divertMbeans = new ArrayList<>();
        JolokiaResponse search = r1.get(i);
        if (search.ok() && search.value() != null && search.value().isArray()) {
            search.value().forEach(n -> divertMbeans.add(n.asString()));
        }

        // Second POST: the MBeans the first one named.
        List<JolokiaRequest> second = new ArrayList<>();
        for (String mbean : divertMbeans) {
            second.add(JolokiaRequest.readAll(mbean));
        }
        List<String> addressesToRead = addressesToRead(scope, settingMatches, addressNames);
        for (String a : addressesToRead) {
            second.add(JolokiaRequest.read(
                    BrokerMBeans.address(broker, a), "RoutingTypes", "AddressSize", "MessageCount"));
        }
        List<String[]> queuesToRead = new ArrayList<>();
        for (BrokerConfigDocument.AddressDecl a : scope.addresses().values()) {
            for (BrokerConfigDocument.QueueDecl q : a.queues()) {
                if (queueNames.contains(q.name())) {
                    queuesToRead.add(new String[] {a.name(), q.name(), q.routingType()});
                    second.add(JolokiaRequest.readAll(BrokerMBeans.queue(broker, a.name(), q.name(), q.routingType())));
                }
            }
        }
        List<JolokiaResponse> r2 = second.isEmpty() ? List.of() : client.batch(second);
        if (!second.isEmpty()) {
            requireCount(r2, second);
        }

        Map<String, DivertDecl> diverts = new LinkedHashMap<>();
        int j = 0;
        for (int k = 0; k < divertMbeans.size(); k++, j++) {
            JolokiaResponse res = r2.get(j);
            if (res.ok() && res.value() != null && res.value().isObject()) {
                DivertRow row = DivertRow.parse(res.value(), nodeId, nodeName);
                diverts.put(
                        row.uniqueName(),
                        new DivertDecl(
                                row.uniqueName(),
                                row.address(),
                                row.forwardingAddress(),
                                row.filter(),
                                row.exclusive(),
                                row.routingType(),
                                row.transformerClassName(),
                                Map.of()));
            }
        }
        Map<String, Set<String>> addresses = new LinkedHashMap<>();
        addressNames.forEach(a -> addresses.put(a, Set.of()));
        Map<String, AddressUsage> usage = new LinkedHashMap<>();
        Map<String, String> boundQueues = new LinkedHashMap<>();
        for (String a : addressesToRead) {
            JolokiaResponse res = r2.get(j++);
            if (!res.ok() || res.value() == null) {
                continue;
            }
            JsonNode v = res.value();
            addresses.put(a, names(v.get("RoutingTypes")));
            usage.put(
                    a,
                    new AddressUsage(
                            v.path("AddressSize").asLong(0),
                            v.path("MessageCount").asLong(0)));
        }
        Map<String, Map<String, Object>> queues = new LinkedHashMap<>();
        for (String[] q : queuesToRead) {
            JolokiaResponse res = r2.get(j++);
            if (res.ok()) {
                queues.put(q[1], queueOps.toQueueConfig(res.value()));
            }
        }
        // Queues that exist but were not declared are known by name; their address is
        // known only when their address MBean was among the ones read above (declared,
        // a divert endpoint, or under a declared match). Anything else is listed by
        // name alone rather than fetched one by one.
        for (String name : queueNames) {
            String address = boundQueues.get(name);
            queues.putIfAbsent(name, address == null ? Map.of("name", name) : Map.of("name", name, "address", address));
        }

        return new ObservedNodeConfig(
                nodeId, nodeName, live, addresses, queues, addressSettings, securitySettings, diverts, usage, null);
    }

    /**
     * Read a node for adoption: every non-system address with its routing types, the
     * settings and roles each address resolves to (the broker cannot report match
     * patterns, §15 M5), the catch-all entries, and every divert. Two batched POSTs,
     * capped at {@link #ADDRESS_READ_CAP} addresses; queues come from the snapshot
     * cache, which already holds their address, routing type and durability.
     */
    public ObservedNodeConfig readForAdoption(JolokiaBrokerClient client, UUID nodeId, String nodeName) {
        String broker = client.resolveBrokerObjectName();
        List<JolokiaRequest> first = List.of(
                JolokiaRequest.read(broker, "Active", "AddressNames"),
                JolokiaRequest.exec(broker, GET_ADDRESS_SETTINGS, DEFAULT_MATCH),
                JolokiaRequest.exec(broker, GET_ROLES, DEFAULT_MATCH),
                JolokiaRequest.search(BrokerMBeans.divertsPattern(broker)));
        List<JolokiaResponse> r1 = client.batch(first);
        requireCount(r1, first);
        JsonNode head = client.parsed(r1.get(0));
        boolean live = head.path("Active").asBoolean(false);
        List<String> addressNames = new TreeSet<>(names(head.get("AddressNames")))
                .stream()
                        .filter(a -> !a.startsWith("activemq.") && !a.startsWith("$sys."))
                        .limit(ADDRESS_READ_CAP)
                        .toList();

        Map<String, Map<String, Object>> addressSettings = new LinkedHashMap<>();
        Map<String, Map<PermissionType, Set<String>>> securitySettings = new LinkedHashMap<>();
        if (r1.get(1).ok()) {
            addressSettings.put(DEFAULT_MATCH, asMap(client.parsed(r1.get(1))));
        }
        if (r1.get(2).ok()) {
            securitySettings.put(DEFAULT_MATCH, roles(client.parsed(r1.get(2))));
        }
        List<String> divertMbeans = new ArrayList<>();
        if (r1.get(3).ok() && r1.get(3).value() != null && r1.get(3).value().isArray()) {
            r1.get(3).value().forEach(n -> divertMbeans.add(n.asString()));
        }

        List<JolokiaRequest> second = new ArrayList<>();
        for (String a : addressNames) {
            second.add(JolokiaRequest.read(BrokerMBeans.address(broker, a), "RoutingTypes"));
            second.add(JolokiaRequest.exec(broker, GET_ADDRESS_SETTINGS, a));
            second.add(JolokiaRequest.exec(broker, GET_ROLES, a));
        }
        divertMbeans.forEach(m -> second.add(JolokiaRequest.readAll(m)));
        List<JolokiaResponse> r2 = second.isEmpty() ? List.of() : client.batch(second);
        if (!second.isEmpty()) {
            requireCount(r2, second);
        }
        Map<String, Set<String>> addresses = new LinkedHashMap<>();
        int j = 0;
        for (String a : addressNames) {
            JolokiaResponse types = r2.get(j++);
            JolokiaResponse settings = r2.get(j++);
            JolokiaResponse roles = r2.get(j++);
            addresses.put(
                    a, types.ok() && types.value() != null ? names(types.value().get("RoutingTypes")) : Set.of());
            if (settings.ok()) {
                addressSettings.put(a, asMap(client.parsed(settings)));
            }
            if (roles.ok()) {
                securitySettings.put(a, roles(client.parsed(roles)));
            }
        }
        Map<String, DivertDecl> diverts = new LinkedHashMap<>();
        for (int k = 0; k < divertMbeans.size(); k++) {
            JolokiaResponse res = r2.get(j++);
            if (res.ok() && res.value() != null && res.value().isObject()) {
                DivertRow row = DivertRow.parse(res.value(), nodeId, nodeName);
                diverts.put(
                        row.uniqueName(),
                        new DivertDecl(
                                row.uniqueName(),
                                row.address(),
                                row.forwardingAddress(),
                                row.filter(),
                                row.exclusive(),
                                row.routingType(),
                                row.transformerClassName(),
                                Map.of()));
            }
        }
        return new ObservedNodeConfig(
                nodeId,
                nodeName,
                live,
                addresses,
                Map.of(),
                addressSettings,
                securitySettings,
                diverts,
                Map.of(),
                null);
    }

    private Map<String, Object> asMap(JsonNode json) {
        if (json == null || !json.isObject()) {
            return Map.of();
        }
        return mapper.convertValue(json, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    /** Declared addresses first, then addresses covered by a declared match, capped. */
    private static List<String> addressesToRead(ReadScope scope, List<String> matches, Set<String> addressNames) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String a : scope.addresses().keySet()) {
            if (addressNames.contains(a)) {
                out.add(a);
            }
        }
        for (String a : scope.divertEndpoints()) {
            if (addressNames.contains(a)) {
                out.add(a);
            }
        }
        for (String a : new TreeSet<>(addressNames)) {
            if (out.size() >= ADDRESS_READ_CAP) {
                break;
            }
            if (a.startsWith("activemq.") || a.startsWith("$sys.")) {
                continue;
            }
            for (String m : matches) {
                if (!m.equals(DEFAULT_MATCH) && AddressMatch.covers(m, a)) {
                    out.add(a);
                    break;
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static Set<String> names(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        if (array != null && array.isArray()) {
            array.forEach(n -> out.add(n.asString()));
        }
        return out;
    }

    /** {@code getRolesAsJSON}'s array of role objects → permission type → roles. */
    static Map<PermissionType, Set<String>> roles(JsonNode array) {
        Map<PermissionType, Set<String>> out = new EnumMap<>(PermissionType.class);
        if (array == null || !array.isArray()) {
            return out;
        }
        for (JsonNode role : array) {
            String name = role.path("name").asString();
            if (name == null || name.isBlank()) {
                continue;
            }
            for (PermissionType t : PermissionType.values()) {
                if (role.path(t.xmlName()).asBoolean(false)) {
                    out.computeIfAbsent(t, k -> new TreeSet<>()).add(name);
                }
            }
        }
        Map<PermissionType, Set<String>> frozen = new EnumMap<>(PermissionType.class);
        out.forEach((t, s) -> frozen.put(t, java.util.Collections.unmodifiableSortedSet((TreeSet<String>) s)));
        return frozen;
    }

    private static void requireCount(List<JolokiaResponse> responses, List<JolokiaRequest> requests) {
        if (responses.size() != requests.size()) {
            throw new io.github.sudoitir.artemisstudio.broker.BrokerConnectionException(
                    io.github.sudoitir.artemisstudio.broker.BrokerConnectionException.Kind.BAD_RESPONSE,
                    "The node answered " + responses.size() + " of " + requests.size() + " batched requests.");
        }
    }
}
