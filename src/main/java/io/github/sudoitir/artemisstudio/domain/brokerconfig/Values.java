package io.github.sudoitir.artemisstudio.domain.brokerconfig;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Value normalisation shared by the planner, the drift comparison and the codec, so
 * that {@code 100} declared as a JSON integer, {@code "100"} pasted from XML and the
 * {@code 100} the broker echoes as a long are one value.
 */
final class Values {

    private Values() {}

    /** Coerce a declared value to the catalogue's type; unknown or untyped keys pass through. */
    static Object normalise(AddressSettingKey key, Object value) {
        if (value == null || key == null) {
            return value;
        }
        return switch (key.type()) {
            case BOOLEAN ->
                value instanceof Boolean b
                        ? b
                        : Boolean.parseBoolean(value.toString().trim());
            case INT, LONG -> {
                BigDecimal n = BrokerConfigValidator.number(value);
                yield n == null ? value : n.longValueExact();
            }
            case DOUBLE -> {
                BigDecimal n = BrokerConfigValidator.number(value);
                yield n == null ? value : n.doubleValue();
            }
            case ENUM -> value.toString().trim().toUpperCase(Locale.ROOT);
            case STRING -> value.toString();
        };
    }

    /** A copy of {@code values} with every known key normalised, sorted by key. */
    static Map<String, Object> normalise(Map<String, Object> values) {
        Map<String, Object> out = new TreeMap<>();
        values.forEach(
                (k, v) -> out.put(k, normalise(AddressSettingKey.byJsonName(k).orElse(null), v)));
        return out;
    }

    /** Equality that ignores numeric width and enum case. */
    static boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return a.toString().equalsIgnoreCase(b.toString());
        }
        BigDecimal na = BrokerConfigValidator.number(a);
        BigDecimal nb = BrokerConfigValidator.number(b);
        if (na != null && nb != null && !(a instanceof String && b instanceof String)) {
            return na.compareTo(nb) == 0;
        }
        return a.toString().equalsIgnoreCase(b.toString());
    }

    /** {@code map} sorted by key, so that a rendered "before → after" is stable. */
    static Map<String, Object> sorted(Map<String, Object> map) {
        return map == null ? Map.of() : new TreeMap<>(map);
    }

    /** A mutable, insertion-ordered copy. */
    static Map<String, Object> mutable(Map<String, Object> map) {
        return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    }
}
