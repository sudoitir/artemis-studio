package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A cluster's declared configuration (ADR-0067 D1, ADR-0091): the sections the
 * management API can apply, as plain records so the same shape is stored as JSON,
 * edited by the form, imported from and exported to {@code broker.xml}, and compared
 * against every live node. {@code version} is the document schema, carried so a later
 * shape can migrate a stored one.
 *
 * <p>Address-setting values are keyed by {@link AddressSettingKey#jsonName()} and hold
 * only the keys the operator declared. Security-setting permissions map a
 * {@link PermissionType} to the roles that hold it. Every collection is normalised on
 * construction — sorted, de-duplicated, never null — so that two documents that mean
 * the same thing compare equal.
 */
public record BrokerConfigDocument(
        int version,
        List<AddressDecl> addresses,
        List<AddressSettingDecl> addressSettings,
        List<SecuritySettingDecl> securitySettings,
        List<DivertDecl> diverts,
        List<BridgeDecl> bridges) {

    public static final int CURRENT_VERSION = 1;

    public BrokerConfigDocument {
        version = version == 0 ? CURRENT_VERSION : version;
        addresses = List.copyOf(addresses == null ? List.of() : addresses);
        addressSettings = List.copyOf(addressSettings == null ? List.of() : addressSettings);
        securitySettings = List.copyOf(securitySettings == null ? List.of() : securitySettings);
        diverts = List.copyOf(diverts == null ? List.of() : diverts);
        // Absent in every revision saved before ADR-0091, and an empty list is the
        // correct reading of those: they declared no bridges.
        bridges = List.copyOf(bridges == null ? List.of() : bridges);
    }

    public static BrokerConfigDocument empty() {
        return new BrokerConfigDocument(CURRENT_VERSION, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return addresses.isEmpty()
                && addressSettings.isEmpty()
                && securitySettings.isEmpty()
                && diverts.isEmpty()
                && bridges.isEmpty();
    }

    /** An address and the queues bound to it. */
    public record AddressDecl(String name, Set<String> routingTypes, List<QueueDecl> queues) {
        public AddressDecl {
            routingTypes = routingTypes == null
                    ? Set.of()
                    : java.util.Collections.unmodifiableSortedSet(new TreeSet<>(routingTypes));
            queues = List.copyOf(queues == null ? List.of() : queues);
        }
    }

    /**
     * A queue as {@code CreateQueueRequest} accepts it. Nullable fields are "not
     * declared", which the plan treats as "the broker's default" — never as a value to
     * write.
     */
    public record QueueDecl(
            String name,
            String routingType,
            String filter,
            Boolean durable,
            Integer maxConsumers,
            Boolean purgeOnNoConsumers,
            Boolean exclusive,
            Boolean nonDestructive,
            Long ringSize) {
        public QueueDecl {
            durable = durable == null || durable;
            filter = filter == null || filter.isBlank() ? null : filter;
        }
    }

    /** One {@code <address-setting match="…">} — only the keys that were declared. */
    public record AddressSettingDecl(String match, Map<String, Object> values) {
        public AddressSettingDecl {
            values = values == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(values));
        }
    }

    /** One {@code <security-setting match="…">}: which roles hold each permission type. */
    public record SecuritySettingDecl(String match, Map<PermissionType, Set<String>> permissions) {
        public SecuritySettingDecl {
            Map<PermissionType, Set<String>> normalised = new LinkedHashMap<>();
            if (permissions != null) {
                for (PermissionType type : PermissionType.values()) {
                    Set<String> roles = permissions.get(type);
                    if (roles != null && !roles.isEmpty()) {
                        normalised.put(type, java.util.Collections.unmodifiableSortedSet(new TreeSet<>(roles)));
                    }
                }
            }
            permissions = java.util.Collections.unmodifiableMap(normalised);
        }

        /** The roles holding one type, empty when none. */
        public Set<String> roles(PermissionType type) {
            return permissions.getOrDefault(type, Set.of());
        }
    }

    /** One divert, on the shape of {@code CreateDivertRequest}. */
    public record DivertDecl(
            String name,
            String address,
            String forwardingAddress,
            String filter,
            boolean exclusive,
            String routingType,
            String transformerClassName,
            Map<String, String> transformerProperties) {
        public DivertDecl {
            filter = filter == null || filter.isBlank() ? null : filter;
            routingType = routingType == null || routingType.isBlank() ? null : routingType.toUpperCase();
            transformerClassName =
                    transformerClassName == null || transformerClassName.isBlank() ? null : transformerClassName;
            transformerProperties = transformerProperties == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new TreeMap<>(transformerProperties));
        }

        /**
         * The transformer as one value, so the editor, the bridge and the divert all
         * carry the same shape. Derived, not stored: the document keeps the two flat
         * fields it has always kept, so revisions written before ADR-0090 read back
         * unchanged.
         */
        @JsonIgnore
        public TransformerDecl transformer() {
            return TransformerDecl.of(transformerClassName, transformerProperties);
        }

        /**
         * Whether the observable properties of two diverts agree.
         *
         * <p>The transformer is part of that: a divert MBean reports
         * {@code TransformerClassName} and {@code TransformerProperties} in full
         * (ADR-0091's measurement), so a changed transformer is drift like any other
         * changed field. It was excluded here on the opposite assumption, which the
         * measurement disproved.
         */
        public boolean sameAs(DivertDecl other) {
            return other != null
                    && Objects.equals(address, other.address)
                    && Objects.equals(forwardingAddress, other.forwardingAddress)
                    && Objects.equals(filter, other.filter)
                    && exclusive == other.exclusive
                    && Objects.equals(effectiveRoutingType(), other.effectiveRoutingType())
                    && Objects.equals(transformer(), other.transformer());
        }

        /** The broker's default when unset is {@code STRIP}. */
        public String effectiveRoutingType() {
            return routingType == null ? "STRIP" : routingType;
        }
    }

    /**
     * A transformer: the class the broker loads and the properties it is configured
     * with. Studio cannot verify that the class is on any broker's classpath before
     * an apply runs (ADR-0090 D6), so nothing here is gated on it — the apply's
     * read-back is the evidence.
     */
    public record TransformerDecl(String className, Map<String, String> properties) {
        public TransformerDecl {
            className = className == null || className.isBlank() ? null : className.trim();
            properties =
                    properties == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(properties));
        }

        /** Null when no class is declared — properties without a class configure nothing. */
        public static TransformerDecl of(String className, Map<String, String> properties) {
            TransformerDecl t = new TransformerDecl(className, properties);
            return t.className() == null ? null : t;
        }
    }

    /**
     * One bridge, on the shape {@code createBridge(String)} accepts (ADR-0091). A null
     * field is "not declared", which the plan treats as the broker's default and never
     * as a value to write.
     *
     * <p>{@code credentialRef} names a credential held in Studio's vault (ADR-0092);
     * the user and the password never reach this document, a revision of it, a diff,
     * an audit parameter, a tool response or an exported fragment.
     */
    public record BridgeDecl(
            String name,
            String queueName,
            String forwardingAddress,
            String filter,
            TransformerDecl transformer,
            List<String> staticConnectors,
            String discoveryGroupName,
            Boolean ha,
            Boolean useDuplicateDetection,
            Long retryInterval,
            Double retryIntervalMultiplier,
            Long maxRetryInterval,
            Integer initialConnectAttempts,
            Integer reconnectAttempts,
            Integer confirmationWindowSize,
            Integer producerWindowSize,
            Integer minLargeMessageSize,
            Long checkPeriod,
            Long connectionTtl,
            String routingType,
            Integer concurrency,
            String clientId,
            String credentialRef) {

        public BridgeDecl {
            filter = filter == null || filter.isBlank() ? null : filter;
            discoveryGroupName =
                    discoveryGroupName == null || discoveryGroupName.isBlank() ? null : discoveryGroupName.trim();
            clientId = clientId == null || clientId.isBlank() ? null : clientId.trim();
            credentialRef = credentialRef == null || credentialRef.isBlank() ? null : credentialRef.trim();
            routingType = routingType == null || routingType.isBlank() ? null : routingType.toUpperCase();
            staticConnectors = staticConnectors == null
                    ? List.of()
                    : staticConnectors.stream()
                            .filter(c -> c != null && !c.isBlank())
                            .map(String::trim)
                            .distinct()
                            .toList();
        }

        /** The broker's default when unset is {@code PASS}. */
        public String effectiveRoutingType() {
            return routingType == null ? "PASS" : routingType;
        }

        /** The broker's default when unset is one worker, which deploys one MBean at the bare name. */
        public int effectiveConcurrency() {
            return concurrency == null || concurrency < 1 ? 1 : concurrency;
        }

        /**
         * Whether the properties <em>the broker reports back</em> agree.
         *
         * <p>{@code BridgeControl} exposes thirteen of the twenty-three fields
         * {@code createBridge} accepts (ADR-0091). The other ten — the window sizes,
         * the large-message size, the check period, the connection TTL, the routing
         * type, the concurrency, the client id, the initial connect attempts and the
         * credential — are write-only from management's point of view, so nothing here
         * compares them: an absence of evidence is not evidence of agreement
         * (ADR-0090 D4a). A field this declaration leaves unset is the broker's own
         * default and is not compared either.
         */
        public boolean sameAs(BridgeDecl other) {
            return other != null
                    && Objects.equals(queueName, other.queueName)
                    && Objects.equals(forwardingAddress, other.forwardingAddress)
                    && Objects.equals(filter, other.filter)
                    && Objects.equals(discoveryGroupName, other.discoveryGroupName)
                    && staticConnectors.equals(other.staticConnectors)
                    && undeclaredOrEqual(ha, other.ha)
                    && undeclaredOrEqual(useDuplicateDetection, other.useDuplicateDetection)
                    && undeclaredOrEqual(retryInterval, other.retryInterval)
                    && undeclaredOrEqual(retryIntervalMultiplier, other.retryIntervalMultiplier)
                    && undeclaredOrEqual(maxRetryInterval, other.maxRetryInterval)
                    && undeclaredOrEqual(reconnectAttempts, other.reconnectAttempts)
                    && Objects.equals(transformer, other.transformer);
        }

        /** The fields of {@link #sameAs} on which this declaration and an observed bridge differ. */
        public List<String> differencesFrom(BridgeDecl observed) {
            List<String> out = new java.util.ArrayList<>();
            if (observed == null) {
                return out;
            }
            compare(out, "queue-name", Objects.equals(queueName, observed.queueName));
            compare(out, "forwarding-address", Objects.equals(forwardingAddress, observed.forwardingAddress));
            compare(out, "filter-string", Objects.equals(filter, observed.filter));
            compare(out, "discovery-group-name", Objects.equals(discoveryGroupName, observed.discoveryGroupName));
            compare(out, "static-connectors", staticConnectors.equals(observed.staticConnectors));
            compare(out, "ha", undeclaredOrEqual(ha, observed.ha));
            compare(
                    out,
                    "use-duplicate-detection",
                    undeclaredOrEqual(useDuplicateDetection, observed.useDuplicateDetection));
            compare(out, "retry-interval", undeclaredOrEqual(retryInterval, observed.retryInterval));
            compare(
                    out,
                    "retry-interval-multiplier",
                    undeclaredOrEqual(retryIntervalMultiplier, observed.retryIntervalMultiplier));
            compare(out, "max-retry-interval", undeclaredOrEqual(maxRetryInterval, observed.maxRetryInterval));
            compare(out, "reconnect-attempts", undeclaredOrEqual(reconnectAttempts, observed.reconnectAttempts));
            compare(out, "transformer", Objects.equals(transformer, observed.transformer));
            return out;
        }

        private static void compare(List<String> out, String field, boolean same) {
            if (!same) {
                out.add(field);
            }
        }

        /** An unset declared value means "the broker's default", which is never compared. */
        private static boolean undeclaredOrEqual(Object declared, Object observed) {
            return declared == null || Objects.equals(declared, observed);
        }
    }
}
