package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/**
 * Compares every node's broker configuration against the others, key by key
 * (ADR-0043, ADR-0178).
 *
 * <p>Each node is flattened to a {@code Map<pointer, value>} with Jackson — already
 * a dependency — and each key is then read across all nodes: its majority value, the
 * nodes that differ from it, and its class. There is no diff library here on purpose:
 * the hard part is semantic, not structural. A generic differ would not know that
 * a message counter is not configuration, that address settings are identified by
 * their {@code match} pattern rather than their position in an array, or that
 * distinct nodes are *supposed* to have different broker names.
 *
 * <p>Two rules follow from that, and they are the substance of the ADR:
 *
 * <ul>
 *   <li><b>Classification, not filtering.</b> A denylist of runtime counters silently
 *       admits every attribute a future Artemis adds; an allowlist silently drops new
 *       configuration. So an allowlist drives the Configuration section and everything
 *       else lands in a visible Unclassified section. Nothing disappears without the
 *       operator being told — the same ethos as "no silently missing buttons".
 *   <li><b>Expected differences are a class, not a suppression.</b> Two distinct nodes
 *       must differ in their name and their node-local paths. Rendering those as drift
 *       makes a healthy pair look broken, and an operator who learns to ignore six
 *       false positives will ignore the seventh entry too.
 * </ul>
 */
public final class ConfigDiff {

    private ConfigDiff() {}

    /** How one key compares across the nodes that expose it. */
    public enum State {
        /** Every compared node has it, with the same value. */
        SAME,
        /** Every compared node has it, with more than one value. */
        DIFFERENT,
        /** At least one compared node does not have it. */
        MISSING_ON_SOME
    }

    /** What a difference in this key means. */
    public enum Classification {
        /** A real configuration key. A difference here is drift. */
        DRIFT,
        /** Correct by design for distinct nodes: name, node-local paths, NodeID. */
        EXPECTED,
        /** Not known to be configuration — a runtime counter, or an attribute Studio has not classified. */
        UNCLASSIFIED
    }

    /**
     * One node's flattened section.
     *
     * @param values pointer → value for every key the node returned
     * @param exposes whether the node's management surface could have returned a key at all; a
     *     key it does not expose and did not return is left out of the comparison instead of
     *     counting as missing there
     */
    public record Side(UUID nodeId, String nodeName, Map<String, String> values, Predicate<String> exposes) {}

    /** A node's value for a key; {@code value} is {@code null} when the key is missing there. */
    public record NodeValue(UUID nodeId, String nodeName, String value) {

        public boolean missing() {
            return value == null;
        }
    }

    /** One distinct value and the nodes that hold it. */
    public record ValueGroup(String value, List<NodeValue> nodes) {}

    /**
     * One key across the nodes.
     *
     * @param key JSON Pointer into the flattened node, e.g. {@code /JournalFileSize}
     *     or {@code /addressSettings/#/maxSizeBytes}
     * @param values every node that counts for this key, in node order
     * @param majority the value more than half of the nodes that return the key hold, or
     *     {@code null} when there is none
     * @param outliers the compared nodes that do not hold the majority value, including nodes
     *     missing the key; empty when there is no majority
     * @param groups every distinct value with its nodes when there is no majority, else empty
     */
    public record Entry(
            String key,
            State state,
            Classification classification,
            String section,
            List<NodeValue> values,
            String majority,
            List<NodeValue> outliers,
            List<ValueGroup> groups) {

        /** True when this is a difference an operator should act on. */
        public boolean isDrift() {
            return state != State.SAME && classification == Classification.DRIFT;
        }

        public boolean isExpected() {
            return state != State.SAME && classification == Classification.EXPECTED;
        }
    }

    /**
     * Broker attributes that are configuration. Everything not named here is
     * Unclassified — visible, but not counted as drift. Derived from the 90-attribute
     * surface both sides of the dev pair expose (surface check §14, Q2), then corrected
     * against a live comparison of that pair.
     *
     * <p>{@code AuthenticationCacheSize} and {@code AuthorizationCacheSize} are
     * deliberately <em>not</em> here despite naming a {@code broker.xml} setting: the
     * MBean reports the cache's <em>current occupancy</em>, not its configured maximum
     * (a healthy pair reads 1/0 and 2/0 against a configured default of 1000). Trusting
     * the name would have made every healthy pair report two drifts.
     */
    private static final Set<String> BROKER_CONFIG_ATTRIBUTES = Set.of(
            "AsyncConnectionExecutionEnabled",
            "BindingsDirectory",
            "BrokerPluginClassNames",
            "ClusterConnectionNames",
            "Clustered",
            "ConnectionTTLOverride",
            "CreateBindingsDir",
            "CreateJournalDir",
            "DiskScanPeriod",
            "FailoverOnServerShutdown",
            "GlobalMaxSize",
            "HAPolicy",
            "IDCacheSize",
            "IncomingInterceptorClassNames",
            "JournalBufferSize",
            "JournalBufferTimeout",
            "JournalCompactMinFiles",
            "JournalCompactPercentage",
            "JournalDirectory",
            "JournalFileSize",
            "JournalMaxIO",
            "JournalMinFiles",
            "JournalPoolFiles",
            "JournalSyncNonTransactional",
            "JournalSyncTransactional",
            "JournalType",
            "LargeMessagesDirectory",
            "ManagementAddress",
            "ManagementNotificationAddress",
            "MaxDiskUsage",
            "MessageCounterEnabled",
            "MessageCounterMaxDayCount",
            "MessageCounterSamplePeriod",
            "MessageExpiryScanPeriod",
            "MessageExpiryThreadPriority",
            "Name",
            "NodeID",
            "OutgoingInterceptorClassNames",
            "PagingDirectory",
            "PersistDeliveryCountBeforeDelivery",
            "PersistIDCache",
            "PersistenceEnabled",
            "ScheduledThreadPoolMaxSize",
            "SecurityEnabled",
            "SecurityInvalidationInterval",
            "SharedStore",
            "ThreadPoolMaxSize",
            "TransactionTimeout",
            "TransactionTimeoutScanPeriod",
            "Version",
            "WildcardRoutingEnabled");

    /**
     * Keys that must differ between two distinct nodes, or that are node-local by
     * nature. Verified against the dev pair rather than assumed: {@code Name} is
     * {@code primary}/{@code backup} and {@code HAPolicy} is
     * "Replication Primary/Backup w/quorum voting" *by design* on a correct pair,
     * while {@code NodeID} and {@code JournalDirectory} are in fact identical there —
     * NodeID only differs when comparing two different logical nodes, and a path only
     * when the deployments genuinely differ.
     */
    private static final Set<String> EXPECTED_DIFFERENT_ATTRIBUTES = Set.of(
            "Name",
            "NodeID",
            "HAPolicy",
            "BindingsDirectory",
            "JournalDirectory",
            "LargeMessagesDirectory",
            "PagingDirectory");

    /** Acceptor parameters that carry a node-local host or port. */
    private static final Set<String> EXPECTED_DIFFERENT_ACCEPTOR_PARAMS = Set.of("host", "port");

    public static final String SECTION_BROKER = "broker";
    public static final String SECTION_ADDRESS_SETTINGS = "addressSettings";
    public static final String SECTION_SECURITY_SETTINGS = "securitySettings";
    public static final String SECTION_ACCEPTORS = "acceptors";

    /**
     * Compare one section across every node.
     *
     * @param section the section name, used to classify keys and to group the result
     * @param sides the flattened nodes, in the order the result lists them
     */
    public static List<Entry> compare(String section, List<Side> sides) {
        Set<String> keys = new LinkedHashSet<>();
        sides.forEach(side -> keys.addAll(side.values().keySet()));

        List<Entry> entries = new ArrayList<>();
        for (String key : keys) {
            List<NodeValue> values = new ArrayList<>();
            for (Side side : sides) {
                String value = side.values().get(key);
                if (value != null || side.exposes().test(key)) {
                    values.add(new NodeValue(side.nodeId(), side.nodeName(), value));
                }
            }
            entries.add(entry(section, key, List.copyOf(values)));
        }
        entries.sort(Comparator.comparing(Entry::key));
        return List.copyOf(entries);
    }

    private static Entry entry(String section, String key, List<NodeValue> values) {
        Map<String, List<NodeValue>> byValue = new LinkedHashMap<>();
        for (NodeValue v : values) {
            if (!v.missing()) {
                byValue.computeIfAbsent(v.value(), x -> new ArrayList<>()).add(v);
            }
        }
        int exposing =
                values.size() - (int) values.stream().filter(NodeValue::missing).count();

        State state;
        if (values.stream().anyMatch(NodeValue::missing)) {
            state = State.MISSING_ON_SOME;
        } else {
            state = byValue.size() > 1 ? State.DIFFERENT : State.SAME;
        }

        String majority = byValue.entrySet().stream()
                .filter(e -> e.getValue().size() * 2 > exposing)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);

        List<NodeValue> outliers = List.of();
        List<ValueGroup> groups = List.of();
        if (state != State.SAME) {
            if (majority != null) {
                outliers =
                        values.stream().filter(v -> !majority.equals(v.value())).toList();
            } else {
                groups = byValue.entrySet().stream()
                        .map(e -> new ValueGroup(e.getKey(), List.copyOf(e.getValue())))
                        .toList();
            }
        }
        return new Entry(key, state, classify(section, key), section, values, majority, outliers, groups);
    }

    /**
     * Which class a key belongs to. Keys are pointers, so the leaf name is the last
     * segment — {@code /addressSettings/orders.#/maxSizeBytes} classifies on
     * {@code maxSizeBytes}, not on the match pattern that identifies the setting.
     */
    public static Classification classify(String section, String key) {
        String leaf = leafOf(key);
        return switch (section) {
            case SECTION_BROKER -> {
                if (EXPECTED_DIFFERENT_ATTRIBUTES.contains(leaf)) {
                    yield Classification.EXPECTED;
                }
                yield BROKER_CONFIG_ATTRIBUTES.contains(leaf) ? Classification.DRIFT : Classification.UNCLASSIFIED;
            }
            // Every field getAddressSettingsAsJSON and getRolesAsJSON return is
            // configuration by construction — they are configuration readers, not
            // statistics readers.
            case SECTION_ADDRESS_SETTINGS, SECTION_SECURITY_SETTINGS -> Classification.DRIFT;
            case SECTION_ACCEPTORS ->
                EXPECTED_DIFFERENT_ACCEPTOR_PARAMS.contains(leaf) ? Classification.EXPECTED : Classification.DRIFT;
            default -> Classification.UNCLASSIFIED;
        };
    }

    private static String leafOf(String pointer) {
        int slash = pointer.lastIndexOf('/');
        return slash < 0 ? pointer : pointer.substring(slash + 1);
    }

    /**
     * Flatten a JSON object to pointer → value. Scalars become their text form;
     * arrays are flattened by index <em>except</em> where {@link #flattenKeyed} is
     * used instead, which is the case for anything with a natural identity.
     */
    public static Map<String, String> flatten(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        flattenInto(node, "", out);
        return out;
    }

    private static void flattenInto(JsonNode node, String prefix, Map<String, String> out) {
        if (node == null || node.isNull()) {
            out.put(prefix.isEmpty() ? "/" : prefix, "null");
            return;
        }
        if (node.isObject()) {
            node.propertyStream().forEach(e -> flattenInto(e.getValue(), prefix + "/" + escape(e.getKey()), out));
            return;
        }
        if (node.isArray()) {
            int i = 0;
            for (JsonNode child : node) {
                flattenInto(child, prefix + "/" + i++, out);
            }
            return;
        }
        out.put(prefix.isEmpty() ? "/" : prefix, node.asString());
    }

    /**
     * Flatten an array of objects keyed by one of their own fields rather than by
     * position — {@code match} for address settings, {@code name} for acceptors and
     * roles. Two nodes returning the same settings in a different order therefore
     * compare as identical: reordering is not drift.
     *
     * <p>An element missing the key field falls back to its index, so it is still
     * shown rather than silently dropped.
     */
    public static Map<String, String> flattenKeyed(JsonNode array, String identityField) {
        Map<String, String> out = new LinkedHashMap<>();
        if (array == null || !array.isArray()) {
            return out;
        }
        int i = 0;
        for (JsonNode element : array) {
            JsonNode id = element.get(identityField);
            String identity = id == null || id.isNull() ? String.valueOf(i) : id.asString();
            flattenInto(element, "/" + escape(identity), out);
            i++;
        }
        return out;
    }

    /** JSON Pointer escaping (RFC 6901): {@code ~} then {@code /}. */
    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    /** A word for a state, for the UI — state is never carried by colour alone. */
    public static String stateWord(State state) {
        return switch (state) {
            case SAME -> "same";
            case DIFFERENT -> "different";
            case MISSING_ON_SOME -> "missing on some";
        };
    }

    /** Lower-cased section label, for grouping in the response. */
    public static String sectionLabel(String section) {
        return switch (section) {
            case SECTION_BROKER -> "Broker";
            case SECTION_ADDRESS_SETTINGS -> "Address settings";
            case SECTION_SECURITY_SETTINGS -> "Security settings";
            case SECTION_ACCEPTORS -> "Acceptors";
            default -> section.toLowerCase(Locale.ROOT);
        };
    }
}
