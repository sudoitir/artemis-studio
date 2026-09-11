package io.github.sudoitir.artemisstudio.domain.brokerconfig;

import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structural validation of a declaration — everything that can be decided without
 * reading a broker. It is strict on purpose: the broker accepts a key it does not
 * know and silently does nothing with it ({@code docs/broker-management-notes.md} §15
 * M1), so a misspelt key that reaches a broker is a change that never happens and
 * nobody is told about.
 *
 * <p>Checks that need the cluster's observed state — a divert's forwarding address,
 * a page size against the merged maximum — live in {@link BrokerConfigPlanner}.
 */
public final class BrokerConfigValidator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^}]*}");
    private static final Set<String> ROUTING_TYPES = Set.of("ANYCAST", "MULTICAST");
    private static final Set<String> DIVERT_ROUTING_TYPES = Set.of("STRIP", "PASS", "ANYCAST", "MULTICAST");
    /** Keys whose numeric value has {@code -1} as "unlimited"; anything lower is a typo. */
    private static final Set<AddressSettingKey> AT_LEAST_MINUS_ONE = Set.of(
            AddressSettingKey.MAX_SIZE_BYTES,
            AddressSettingKey.MAX_SIZE_MESSAGES,
            AddressSettingKey.PAGE_LIMIT_BYTES,
            AddressSettingKey.PAGE_LIMIT_MESSAGES,
            AddressSettingKey.MAX_DELIVERY_ATTEMPTS,
            AddressSettingKey.EXPIRY_DELAY,
            AddressSettingKey.MIN_EXPIRY_DELAY,
            AddressSettingKey.MAX_EXPIRY_DELAY,
            AddressSettingKey.REDISTRIBUTION_DELAY,
            AddressSettingKey.SLOW_CONSUMER_THRESHOLD,
            AddressSettingKey.DEFAULT_RING_SIZE,
            AddressSettingKey.DEFAULT_MAX_CONSUMERS,
            AddressSettingKey.MAX_REDELIVERY_DELAY,
            AddressSettingKey.MAX_READ_PAGE_BYTES,
            AddressSettingKey.MAX_READ_PAGE_MESSAGES,
            AddressSettingKey.PREFETCH_PAGE_BYTES,
            AddressSettingKey.PREFETCH_PAGE_MESSAGES);

    private BrokerConfigValidator() {}

    public static List<Violation> validate(BrokerConfigDocument doc) {
        List<Violation> out = new ArrayList<>();
        addresses(doc, out);
        addressSettings(doc, out);
        securitySettings(doc, out);
        diverts(doc, out);
        return List.copyOf(out);
    }

    // ---- addresses and queues -------------------------------------------

    private static void addresses(BrokerConfigDocument doc, List<Violation> out) {
        Set<String> addressNames = new HashSet<>();
        Set<String> queueNames = new HashSet<>();
        for (int i = 0; i < doc.addresses().size(); i++) {
            AddressDecl a = doc.addresses().get(i);
            String p = "addresses[" + i + "]";
            if (blank(a.name())) {
                out.add(new Violation(p + ".name", "An address needs a name."));
            } else if (!addressNames.add(a.name())) {
                out.add(new Violation(p + ".name", "Address '" + a.name() + "' is declared twice."));
            }
            placeholder(a.name(), p + ".name", out);
            if (a.routingTypes().isEmpty()) {
                out.add(new Violation(p + ".routingTypes", "An address needs at least one routing type."));
            }
            for (String rt : a.routingTypes()) {
                if (!ROUTING_TYPES.contains(rt)) {
                    out.add(new Violation(
                            p + ".routingTypes", "Routing type must be ANYCAST or MULTICAST, not '" + rt + "'."));
                }
            }
            for (int j = 0; j < a.queues().size(); j++) {
                QueueDecl q = a.queues().get(j);
                String qp = p + ".queues[" + j + "]";
                if (blank(q.name())) {
                    out.add(new Violation(qp + ".name", "A queue needs a name."));
                } else if (!queueNames.add(q.name())) {
                    out.add(new Violation(
                            qp + ".name",
                            "Queue '" + q.name() + "' is declared twice; queue names are unique per broker."));
                }
                if (blank(q.routingType())
                        || !ROUTING_TYPES.contains(q.routingType().toUpperCase(Locale.ROOT))) {
                    out.add(new Violation(qp + ".routingType", "A queue's routing type must be ANYCAST or MULTICAST."));
                } else if (!a.routingTypes().isEmpty()
                        && !a.routingTypes().contains(q.routingType().toUpperCase(Locale.ROOT))) {
                    out.add(new Violation(
                            qp + ".routingType",
                            "Queue '" + q.name() + "' is " + q.routingType().toUpperCase(Locale.ROOT)
                                    + " but its address only routes " + String.join(", ", a.routingTypes()) + "."));
                }
                if (q.maxConsumers() != null && q.maxConsumers() < -1) {
                    out.add(new Violation(qp + ".maxConsumers", "Max consumers is -1 for unlimited or a count."));
                }
                if (q.ringSize() != null && q.ringSize() < -1) {
                    out.add(new Violation(qp + ".ringSize", "Ring size is -1 for unlimited or a count."));
                }
                placeholder(q.filter(), qp + ".filter", out);
            }
        }
    }

    // ---- address settings ------------------------------------------------

    private static void addressSettings(BrokerConfigDocument doc, List<Violation> out) {
        Set<String> matches = new HashSet<>();
        for (int i = 0; i < doc.addressSettings().size(); i++) {
            AddressSettingDecl s = doc.addressSettings().get(i);
            String p = "addressSettings[" + i + "]";
            if (blank(s.match())) {
                out.add(new Violation(p + ".match", "An address setting needs a match pattern."));
            } else if (!matches.add(s.match())) {
                out.add(new Violation(p + ".match", "Match '" + s.match() + "' is declared twice."));
            }
            if (s.values().isEmpty()) {
                out.add(new Violation(
                        p + ".values", "An address setting with no keys changes nothing; remove it or declare a key."));
            }
            for (Map.Entry<String, Object> e : s.values().entrySet()) {
                String vp = p + ".values." + e.getKey();
                AddressSettingKey key = AddressSettingKey.byJsonName(e.getKey()).orElse(null);
                if (key == null) {
                    out.add(new Violation(
                            vp,
                            "'" + e.getKey()
                                    + "' is not an address-setting key Studio knows. The broker would accept it"
                                    + " and silently ignore it."));
                    continue;
                }
                if (!key.applicable()) {
                    out.add(new Violation(
                            vp,
                            key.xmlName() + " governs what a configuration-file reload removes and is not applied at"
                                    + " runtime. Declare it in broker.xml."));
                    continue;
                }
                value(key, e.getValue(), vp, out);
            }
            BigDecimal max = number(s.values().get(AddressSettingKey.MAX_SIZE_BYTES.jsonName()));
            BigDecimal page = number(s.values().get(AddressSettingKey.PAGE_SIZE_BYTES.jsonName()));
            if (max != null && page != null && max.signum() >= 0 && page.compareTo(max) >= 0) {
                out.add(new Violation(
                        p + ".values.pageSizeBytes",
                        "page-size-bytes must be lower than max-size-bytes; the broker refuses the pair."));
            }
        }
    }

    private static void value(AddressSettingKey key, Object value, String path, List<Violation> out) {
        if (value == null) {
            out.add(new Violation(path, key.xmlName() + " has no value."));
            return;
        }
        if (value instanceof String s) {
            placeholder(s, path, out);
        }
        switch (key.type()) {
            case BOOLEAN -> {
                if (!(value instanceof Boolean) && !isBooleanText(value)) {
                    out.add(new Violation(path, key.xmlName() + " must be true or false."));
                }
            }
            case INT, LONG -> {
                BigDecimal n = number(value);
                if (n == null || n.scale() > 0 && n.stripTrailingZeros().scale() > 0) {
                    out.add(new Violation(path, key.xmlName() + " must be a whole number."));
                } else if (AT_LEAST_MINUS_ONE.contains(key) && n.compareTo(BigDecimal.ONE.negate()) < 0) {
                    out.add(new Violation(path, key.xmlName() + " is -1 for unlimited or a non-negative number."));
                } else if (!AT_LEAST_MINUS_ONE.contains(key) && n.signum() < 0) {
                    out.add(new Violation(path, key.xmlName() + " cannot be negative."));
                } else if (key.type() == AddressSettingKey.Type.INT
                        && n.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                    out.add(new Violation(path, key.xmlName() + " is larger than the broker's 32-bit limit."));
                }
            }
            case DOUBLE -> {
                BigDecimal n = number(value);
                if (n == null) {
                    out.add(new Violation(path, key.xmlName() + " must be a number."));
                } else if (key == AddressSettingKey.REDELIVERY_DELAY_MULTIPLIER && n.signum() <= 0) {
                    out.add(new Violation(path, "redelivery-delay-multiplier must be greater than zero."));
                } else if (key == AddressSettingKey.REDELIVERY_COLLISION_AVOIDANCE_FACTOR
                        && (n.signum() < 0 || n.compareTo(BigDecimal.ONE) > 0)) {
                    out.add(new Violation(path, "redelivery-collision-avoidance-factor is between 0 and 1."));
                }
            }
            case STRING -> {
                if (!(value instanceof String)) {
                    out.add(new Violation(path, key.xmlName() + " must be text."));
                }
            }
            case ENUM -> {
                String text = value.toString().toUpperCase(Locale.ROOT);
                if (!key.allowedValues().contains(text)) {
                    out.add(new Violation(
                            path, key.xmlName() + " must be one of " + String.join(", ", key.allowedValues()) + "."));
                }
            }
        }
    }

    // ---- security settings ---------------------------------------------

    private static void securitySettings(BrokerConfigDocument doc, List<Violation> out) {
        Set<String> matches = new HashSet<>();
        for (int i = 0; i < doc.securitySettings().size(); i++) {
            SecuritySettingDecl s = doc.securitySettings().get(i);
            String p = "securitySettings[" + i + "]";
            if (blank(s.match())) {
                out.add(new Violation(p + ".match", "A security setting needs a match pattern."));
            } else if (!matches.add(s.match())) {
                out.add(new Violation(p + ".match", "Match '" + s.match() + "' is declared twice."));
            }
            if (s.permissions().isEmpty()) {
                out.add(new Violation(
                        p + ".permissions",
                        "A security setting with no permissions denies everything under its match. Declare at least"
                                + " one role, or remove it."));
            }
            for (Map.Entry<PermissionType, Set<String>> e : s.permissions().entrySet()) {
                for (String role : e.getValue()) {
                    if (blank(role) || role.contains(",")) {
                        out.add(new Violation(
                                p + ".permissions." + e.getKey().xmlName(),
                                "Role names are single words without commas; '" + role + "' is not."));
                    }
                    placeholder(role, p + ".permissions." + e.getKey().xmlName(), out);
                }
            }
        }
    }

    // ---- diverts ---------------------------------------------------------

    private static void diverts(BrokerConfigDocument doc, List<Violation> out) {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < doc.diverts().size(); i++) {
            DivertDecl d = doc.diverts().get(i);
            String p = "diverts[" + i + "]";
            if (blank(d.name())) {
                out.add(new Violation(p + ".name", "A divert needs a name."));
            } else if (!names.add(d.name())) {
                out.add(new Violation(p + ".name", "Divert '" + d.name() + "' is declared twice."));
            }
            if (blank(d.address())) {
                out.add(new Violation(p + ".address", "A divert needs the address it reads from."));
            }
            if (blank(d.forwardingAddress())) {
                out.add(new Violation(p + ".forwardingAddress", "A divert needs the address it forwards to."));
            } else if (d.forwardingAddress().equals(d.address())) {
                out.add(new Violation(
                        p + ".forwardingAddress", "A divert cannot forward to the address it reads from."));
            }
            if (d.routingType() != null && !DIVERT_ROUTING_TYPES.contains(d.routingType())) {
                out.add(new Violation(
                        p + ".routingType", "A divert's routing type is STRIP, PASS, ANYCAST or MULTICAST."));
            }
            placeholder(d.filter(), p + ".filter", out);
            placeholder(d.forwardingAddress(), p + ".forwardingAddress", out);
        }
    }

    // ---- helpers ---------------------------------------------------------

    private static void placeholder(String value, String path, List<Violation> out) {
        if (value != null && PLACEHOLDER.matcher(value).find()) {
            out.add(new Violation(
                    path, "'" + value + "' is a placeholder. Studio cannot resolve it; enter the concrete value."));
        }
    }

    static BigDecimal number(Object value) {
        return switch (value) {
            case null -> null;
            case BigDecimal b -> b;
            case Number n -> new BigDecimal(n.toString());
            case String s -> {
                try {
                    yield new BigDecimal(s.trim());
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            default -> null;
        };
    }

    private static boolean isBooleanText(Object value) {
        return value instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
