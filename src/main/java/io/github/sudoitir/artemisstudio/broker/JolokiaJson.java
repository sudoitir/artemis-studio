package io.github.sudoitir.artemisstudio.broker;

import tools.jackson.databind.JsonNode;

/** Null-safe readers for the loosely-typed {@code value} objects Jolokia returns. */
public final class JolokiaJson {

    private JolokiaJson() {}

    /** A string field, or {@code null} when absent or JSON null. */
    public static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** A boolean field, {@code false} when absent. */
    public static boolean bool(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.asBoolean();
    }

    /** A boolean field, {@code null} when absent or JSON null. */
    public static Boolean boxedBool(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asBoolean();
    }
}
