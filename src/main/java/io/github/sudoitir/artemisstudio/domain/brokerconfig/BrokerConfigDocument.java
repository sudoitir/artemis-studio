package io.github.sudoitir.artemisstudio.domain.brokerconfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A cluster's declared configuration (ADR-0067 D1): the four sections the management
 * API can apply, as plain records so the same shape is stored as JSON, edited by the
 * form, imported from and exported to {@code broker.xml}, and compared against every
 * live node. {@code version} is the document schema, carried so a later shape can
 * migrate a stored one.
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
        List<DivertDecl> diverts) {

    public static final int CURRENT_VERSION = 1;

    public BrokerConfigDocument {
        version = version == 0 ? CURRENT_VERSION : version;
        addresses = List.copyOf(addresses == null ? List.of() : addresses);
        addressSettings = List.copyOf(addressSettings == null ? List.of() : addressSettings);
        securitySettings = List.copyOf(securitySettings == null ? List.of() : securitySettings);
        diverts = List.copyOf(diverts == null ? List.of() : diverts);
    }

    public static BrokerConfigDocument empty() {
        return new BrokerConfigDocument(CURRENT_VERSION, List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return addresses.isEmpty() && addressSettings.isEmpty() && securitySettings.isEmpty() && diverts.isEmpty();
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

        /** Whether the observable properties of two diverts agree (transformer excluded: the broker does not report it fully). */
        public boolean sameAs(DivertDecl other) {
            return other != null
                    && Objects.equals(address, other.address)
                    && Objects.equals(forwardingAddress, other.forwardingAddress)
                    && Objects.equals(filter, other.filter)
                    && exclusive == other.exclusive
                    && Objects.equals(effectiveRoutingType(), other.effectiveRoutingType());
        }

        /** The broker's default when unset is {@code STRIP}. */
        public String effectiveRoutingType() {
            return routingType == null ? "STRIP" : routingType;
        }
    }
}
