package io.github.sudoitir.artemisstudio.platform.broker;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The one shared list operation behind all six cross-node views. Every
 * {@code listX(String,int,int)} management op returns the same envelope — a
 * JSON-encoded {@code {"data":[…],"count":N}} string (Phase 0) — and every scalar
 * inside a row is a quoted string. This centralises the double-decode and the
 * string→type coercion so callers never re-implement it.
 */
@Component
public class BrokerListOps {

    /** One page of one op on one node. {@code page}/{@code size} of {@code -1} disables paging. */
    public record ListPage(JsonNode data, long count) {}

    public ListPage fetch(JolokiaBrokerClient client, String op, String options, int page, int size) {
        JsonNode env = client.execOnBrokerParsed(op + "(java.lang.String,int,int)", options, page, size);
        JsonNode data = env == null ? null : env.get("data");
        long count = env == null ? 0L : env.path("count").asLong(data != null && data.isArray() ? data.size() : 0);
        return new ListPage(data, count);
    }

    /**
     * One batched POST reading every named MBean in full. A single MBean that has
     * disappeared between a search and this read contributes nothing rather than
     * failing the page — routing changes under us, and a half-listed view is more
     * useful than an error.
     */
    public static <T> List<T> readAll(
            JolokiaBrokerClient client, List<String> objectNames, Function<JsonNode, T> parse) {
        if (objectNames.isEmpty()) {
            return List.of();
        }
        List<JolokiaResponse> responses =
                client.batch(objectNames.stream().map(JolokiaRequest::readAll).toList());
        List<T> rows = new ArrayList<>();
        for (JolokiaResponse response : responses) {
            if (response.ok() && response.value() != null && response.value().isObject()) {
                rows.add(parse.apply(response.value()));
            }
        }
        return List.copyOf(rows);
    }

    public static long num(JsonNode row, String field) {
        JsonNode v = row == null ? null : row.get(field);
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

    public static boolean flag(JsonNode row, String field) {
        JsonNode v = row == null ? null : row.get(field);
        return v != null && !v.isNull() && (v.isBoolean() ? v.asBoolean() : Boolean.parseBoolean(v.asText()));
    }

    public static String str(JsonNode row, String field) {
        JsonNode v = row == null ? null : row.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
