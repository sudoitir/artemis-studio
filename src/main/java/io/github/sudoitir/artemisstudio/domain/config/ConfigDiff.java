package io.github.sudoitir.artemisstudio.domain.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Compares two nodes' broker configuration, key by key (ADR-0043).
 *
 * <p>Each side is flattened to a {@code Map<pointer, value>} with Jackson — already
 * a dependency — and compared three ways. There is no diff library here on purpose:
 * the hard part is semantic, not structural. A generic differ would not know that
 * a message counter is not configuration, that address settings are identified by
 * their {@code match} pattern rather than their position in an array, or that a
 * primary and its backup are *supposed* to have different broker names.
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

    /** How one key compares across the two sides. */
    public enum KeyStatus {
        SAME,
        DIFFERENT,
        ONLY_IN_LEFT,
        ONLY_IN_RIGHT
    }

    /** What a difference in this key means. */
    public enum Classification {
        /** A real configuration key. A difference here is drift. */
        CONFIGURATION,
        /** Correct by design for two distinct nodes: name, node-local paths, NodeID. */
        EXPECTED,
        /** Not known to be configuration — a runtime counter, or an attribute Studio has not classified. */
        UNCLASSIFIED
    }

    /**
     * @param key JSON Pointer into the flattened side, e.g. {@code /JournalFileSize}
     *     or {@code /addressSettings/#/maxSizeBytes}
     * @param left the left node's value, or {@code null} when the key is absent there
     * @param right the right node's value, or {@code null} when absent
     */
    public record Entry(
            String key, String left, String right, KeyStatus status, Classification classification, String section) {

        /** True when this is a difference an operator should act on. */
        public boolean isDrift() {
            return status != KeyStatus.SAME && classification == Classification.CONFIGURATION;
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
     * Compare one section's already-flattened sides.
     *
     * @param section the section name, used to classify keys and to group the result
     * @param left flattened left side, pointer → value
     * @param right flattened right side
     */
    public static List<Entry> compare(String section, Map<String, String> left, Map<String, String> right) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(left.keySet());
        keys.addAll(right.keySet());

        List<Entry> entries = new ArrayList<>();
        for (String key : keys) {
            String l = left.get(key);
            String r = right.get(key);
            KeyStatus status;
            if (l == null) {
                status = KeyStatus.ONLY_IN_RIGHT;
            } else if (r == null) {
                status = KeyStatus.ONLY_IN_LEFT;
            } else {
                status = l.equals(r) ? KeyStatus.SAME : KeyStatus.DIFFERENT;
            }
            entries.add(new Entry(key, l, r, status, classify(section, key), section));
        }
        entries.sort(Comparator.comparing(Entry::key));
        return List.copyOf(entries);
    }

    /**
     * Which class a key belongs to. Keys are pointers, so the leaf name is the last
     * segment — {@code /addressSettings/orders.#/maxSizeBytes} classifies on
     * {@code maxSizeBytes}, not on the match pattern that identifies the setting.
     */
    static Classification classify(String section, String key) {
        String leaf = leafOf(key);
        return switch (section) {
            case SECTION_BROKER -> {
                if (EXPECTED_DIFFERENT_ATTRIBUTES.contains(leaf)) {
                    yield Classification.EXPECTED;
                }
                yield BROKER_CONFIG_ATTRIBUTES.contains(leaf)
                        ? Classification.CONFIGURATION
                        : Classification.UNCLASSIFIED;
            }
            // Every field getAddressSettingsAsJSON and getRolesAsJSON return is
            // configuration by construction — they are configuration readers, not
            // statistics readers.
            case SECTION_ADDRESS_SETTINGS, SECTION_SECURITY_SETTINGS -> Classification.CONFIGURATION;
            case SECTION_ACCEPTORS ->
                EXPECTED_DIFFERENT_ACCEPTOR_PARAMS.contains(leaf)
                        ? Classification.EXPECTED
                        : Classification.CONFIGURATION;
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

    /** A word for a status, for the UI — status is never carried by colour alone. */
    public static String statusWord(KeyStatus status, String leftName, String rightName) {
        return switch (status) {
            case SAME -> "same";
            case DIFFERENT -> "different";
            case ONLY_IN_LEFT -> "only on " + leftName;
            case ONLY_IN_RIGHT -> "only on " + rightName;
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
