package io.github.sudoitir.artemisstudio.broker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Reads messages off a queue over Jolokia (ADR-0021 — Phase 3 message I/O is
 * Jolokia-only). One batched POST per browse: {@code browse(int,int,String)} for
 * the page plus a {@code MessageCount} read for the total, so the broker pages
 * and Studio never pulls a whole deep queue through its heap (non-negotiable #1).
 *
 * <p>Unlike the {@code listX} envelopes, {@code browse()}'s {@code value} is a
 * plain JSON array — no double-decode. Every scalar is already typed. The broker
 * truncates oversized body / property strings at
 * {@code management-message-attribute-size-limit} and appends a literal
 * {@code , + N more} marker; that marker is the only truncation signal there is
 * (slice 0), so {@link #TRUNCATION_MARKER} detection drives the per-message
 * {@code bodyTruncated} flag.
 */
@Component
public class MessageBrowser {

    /** {@code managementBrowsePageSize} default — the broker will not return more per page. */
    public static final int BROKER_PAGE_CAP = 200;

    /** Artemis' verbatim truncation suffix, e.g. {@code ", + 3744 more"}. */
    static final Pattern TRUNCATION_MARKER = Pattern.compile(", \\+ \\d+ more$");

    private static final String OP_BROWSE = "browse(int,int,java.lang.String)";
    private static final String ATTR_MESSAGE_COUNT = "MessageCount";

    /** How to read {@link BrowsedMessage#body()}: as UTF-8 text, or as base64-encoded bytes. */
    public enum BodyEncoding {
        TEXT,
        BASE64
    }

    /** One decoded message: the header set plus five typed property maps and the body. */
    public record BrowsedMessage(
            long messageId,
            int type,
            boolean durable,
            int priority,
            long timestamp,
            long expiration,
            long size,
            String groupId,
            String correlationId,
            String userId,
            String body,
            BodyEncoding bodyEncoding,
            String contentType,
            boolean bodyTruncated,
            Integer observedLimitBytes,
            Map<String, String> stringProperties,
            Map<String, Long> intProperties,
            Map<String, Long> longProperties,
            Map<String, Double> doubleProperties,
            Map<String, Boolean> booleanProperties) {

        public int propertyCount() {
            return stringProperties.size()
                    + intProperties.size()
                    + longProperties.size()
                    + doubleProperties.size()
                    + booleanProperties.size();
        }

        /** First ~200 chars of the body, for the grid cell. */
        public String bodyPreview() {
            if (body == null) {
                return null;
            }
            return body.length() <= 200 ? body : body.substring(0, 200);
        }
    }

    public record BrowsePage(List<BrowsedMessage> messages, long total) {}

    /**
     * One page of one queue. {@code page} is 1-based; the broker caps
     * {@code size} at {@code managementBrowsePageSize} (200 by default).
     *
     * @throws IllegalArgumentException the filter is not valid selector syntax
     *     ({@code AMQ229020}) — maps to a 400
     */
    public BrowsePage browse(JolokiaBrokerClient client, String queueMbean, int page, int size, String filter) {
        String selector = filter == null ? "" : filter;
        List<JolokiaResponse> responses = client.batch(List.of(
                JolokiaRequest.exec(queueMbean, OP_BROWSE, page, size, selector),
                JolokiaRequest.read(queueMbean, ATTR_MESSAGE_COUNT)));

        JolokiaResponse browse = responses.get(0);
        if (!browse.ok()) {
            String error = browse.error() == null ? "" : browse.error();
            if (error.contains("AMQ229020") || error.toLowerCase().contains("invalid filter")) {
                throw new IllegalArgumentException("Invalid message filter: " + selector);
            }
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.BAD_RESPONSE,
                    "browse() failed: " + (error.isEmpty() ? "status " + browse.status() : error));
        }

        java.util.ArrayList<BrowsedMessage> messages = new java.util.ArrayList<>();
        JsonNode array = browse.value();
        if (array != null && array.isArray()) {
            array.forEach(row -> messages.add(decodeRow(row)));
        }

        JolokiaResponse count = responses.size() > 1 ? responses.get(1) : null;
        long total = count != null
                        && count.ok()
                        && count.value() != null
                        && count.value().isNumber()
                ? count.value().asLong()
                : messages.size();
        return new BrowsePage(List.copyOf(messages), total);
    }

    private static BrowsedMessage decodeRow(JsonNode row) {
        String body = text(row, "text");
        Map<String, String> strings = stringMap(row, "StringProperties");

        boolean truncated = isTruncated(body);
        Integer observedLimit = truncated ? observedLimit(body) : null;
        if (!truncated) {
            for (String v : strings.values()) {
                if (isTruncated(v)) {
                    truncated = true;
                    observedLimit = observedLimit(v);
                    break;
                }
            }
        }

        return new BrowsedMessage(
                asLong(row, "messageID"),
                (int) asLong(row, "type"),
                asBool(row, "durable"),
                (int) asLong(row, "priority"),
                asLong(row, "timestamp"),
                asLong(row, "expiration"),
                asLong(row, "persistentSize"),
                text(row, "groupID"),
                text(row, "correlationID"),
                blankToNull(text(row, "userID")),
                body,
                BodyEncoding.TEXT, // Jolokia browse() always stringifies
                null,
                truncated,
                observedLimit,
                strings,
                longMap(row, "IntProperties", "ShortProperties", "ByteProperties"),
                longMap(row, "LongProperties"),
                doubleMap(row, "DoubleProperties", "FloatProperties"),
                boolMap(row, "BooleanProperties"));
    }

    // ---- truncation ------------------------------------------------------

    private static boolean isTruncated(String value) {
        return value != null && TRUNCATION_MARKER.matcher(value).find();
    }

    /** Chars that survived truncation = full length minus the {@code , + N more} marker. */
    private static Integer observedLimit(String value) {
        var matcher = TRUNCATION_MARKER.matcher(value);
        return matcher.find() ? matcher.start() : null;
    }

    // ---- scalar coercion (browse values are already typed, but be defensive) ----

    private static long asLong(JsonNode row, String field) {
        JsonNode v = row.get(field);
        if (v == null || v.isNull()) {
            return 0L;
        }
        if (v.isNumber()) {
            return v.asLong();
        }
        try {
            return Long.parseLong(v.asText().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean asBool(JsonNode row, String field) {
        JsonNode v = row.get(field);
        return v != null && !v.isNull() && (v.isBoolean() ? v.asBoolean() : Boolean.parseBoolean(v.asText()));
    }

    private static String text(JsonNode row, String field) {
        JsonNode v = row.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    // ---- typed property maps -------------------------------------------

    private static Map<String, String> stringMap(JsonNode row, String field) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode node = row.get(field);
        if (node != null && node.isObject()) {
            node.properties().forEach(e -> out.put(e.getKey(), e.getValue().asText()));
        }
        return out;
    }

    private static Map<String, Long> longMap(JsonNode row, String... fields) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String field : fields) {
            JsonNode node = row.get(field);
            if (node != null && node.isObject()) {
                node.properties().forEach(e -> out.put(e.getKey(), e.getValue().asLong()));
            }
        }
        return out;
    }

    private static Map<String, Double> doubleMap(JsonNode row, String... fields) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String field : fields) {
            JsonNode node = row.get(field);
            if (node != null && node.isObject()) {
                node.properties().forEach(e -> out.put(e.getKey(), e.getValue().asDouble()));
            }
        }
        return out;
    }

    private static Map<String, Boolean> boolMap(JsonNode row, String field) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        JsonNode node = row.get(field);
        if (node != null && node.isObject()) {
            node.properties().forEach(e -> out.put(e.getKey(), e.getValue().asBoolean()));
        }
        return out;
    }
}
