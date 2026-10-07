package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one written form of an operation's parameters, which a held request is bound to (ADR-0180):
 * object keys sorted by UTF-16 code unit, null members left out, strings NFC-normalized, instants as
 * ISO-8601 UTC, UUIDs in lowercase, enums by name, no whitespace, at most {@value #MAX_BYTES} bytes
 * of UTF-8. Floating-point numbers are refused, because two writers need not agree on them, and so
 * are nulls inside arrays. String escaping follows RFC 8785. The mapper is Studio's own and fixed, so
 * the form never changes with the application's JSON settings.
 */
@PluginApi
public final class CanonicalJson {

    public static final int MAX_BYTES = 64 * 1024;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(EnumFeature.WRITE_ENUMS_USING_TO_STRING)
            .build();

    private CanonicalJson() {}

    /**
     * The canonical form of {@code value}, usually an operation's parameter record.
     *
     * @throws IllegalArgumentException naming the JSON Pointer of a float or an array null, or when
     *     the result is larger than {@value #MAX_BYTES} bytes
     */
    public static String write(Object value) {
        JsonNode tree = MAPPER.valueToTree(value);
        if (tree == null || tree.isNull()) {
            throw new IllegalArgumentException("Parameters must not be null");
        }
        StringBuilder out = new StringBuilder();
        append(tree, "", out);
        int bytes = out.toString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "Parameters are " + bytes + " bytes, more than the " + MAX_BYTES + " a held request may carry");
        }
        return out.toString();
    }

    /** {@code SHA-256(type ‖ LF ‖ version ‖ LF ‖ canonical)}, over UTF-8. */
    public static byte[] hash(String type, int version, String canonical) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return sha.digest((type + '\n' + version + '\n' + canonical).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }

    private static void append(JsonNode node, String path, StringBuilder out) {
        if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> members = new ArrayList<>();
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                if (!member.getValue().isNull()) {
                    members.add(Map.entry(nfc(member.getKey(), path), member.getValue()));
                }
            }
            members.sort(Map.Entry.comparingByKey());
            out.append('{');
            for (int i = 0; i < members.size(); i++) {
                Map.Entry<String, JsonNode> member = members.get(i);
                if (i > 0) {
                    if (member.getKey().equals(members.get(i - 1).getKey())) {
                        throw new IllegalArgumentException(path + "/" + member.getKey() + ": duplicate key after NFC");
                    }
                    out.append(',');
                }
                string(member.getKey(), out);
                out.append(':');
                append(member.getValue(), path + "/" + member.getKey(), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            int i = 0;
            for (JsonNode item : node.values()) {
                if (item.isNull()) {
                    throw new IllegalArgumentException(path + "/" + i + ": null is not allowed in an array");
                }
                if (i > 0) {
                    out.append(',');
                }
                append(item, path + "/" + i, out);
                i++;
            }
            out.append(']');
        } else if (node.isString()) {
            string(nfc(node.stringValue(), path), out);
        } else if (node.isIntegralNumber()) {
            out.append(node.bigIntegerValue());
        } else if (node.isNumber()) {
            throw new IllegalArgumentException((path.isEmpty() ? "/" : path)
                    + ": floating-point numbers are not allowed; use an integer or a string");
        } else if (node.isBoolean()) {
            out.append(node.booleanValue());
        } else {
            throw new IllegalArgumentException(
                    (path.isEmpty() ? "/" : path) + ": " + node.getNodeType() + " has no canonical form");
        }
    }

    private static String nfc(String value, String path) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)
                    && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new IllegalArgumentException((path.isEmpty() ? "/" : path) + ": unpaired surrogate");
            }
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private static void string(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
