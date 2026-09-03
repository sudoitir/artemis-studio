package io.github.sudoitir.artemisstudio.broker;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * One parsed row of {@code listQueues(...)}, coerced from Artemis' all-strings
 * JSON into real types and tagged with the cluster/node it came from.
 *
 * <p>Phase 0: every scalar in a {@code listQueues} row is a quoted string
 * ({@code "messageCount":"0"}, {@code "durable":"true"}). Parse per field.
 * {@code internalQueue=true} rows are dropped — they are broker plumbing, not
 * operator-facing queues.
 */
public record QueueRow(
        UUID clusterId,
        UUID nodeId,
        String address,
        String queueName,
        String routingType,
        boolean durable,
        long messageCount,
        long consumerCount,
        long deliveringCount,
        long scheduledCount,
        long messagesAdded,
        long messagesAcked,
        long messagesExpired) {

    /** Parse a {@code {"data":[...],"count":N}} page's {@code data} array, skipping internal queues. */
    public static List<QueueRow> parsePage(JsonNode dataArray, UUID clusterId, UUID nodeId) {
        List<QueueRow> rows = new ArrayList<>();
        if (dataArray == null || !dataArray.isArray()) {
            return rows;
        }
        for (JsonNode row : dataArray) {
            if (flag(row, "internalQueue")) {
                continue;
            }
            String routingType = str(row, "routingType");
            rows.add(new QueueRow(
                    clusterId,
                    nodeId,
                    str(row, "address"),
                    str(row, "name"),
                    routingType == null ? "ANYCAST" : routingType.toUpperCase(),
                    flag(row, "durable"),
                    num(row, "messageCount"),
                    num(row, "consumerCount"),
                    num(row, "deliveringCount"),
                    num(row, "scheduledCount"),
                    num(row, "messagesAdded"),
                    num(row, "messagesAcked"),
                    num(row, "messagesExpired")));
        }
        return rows;
    }

    /** True when this queue had traffic worth re-reading on the fast tier. */
    public boolean busy() {
        return consumerCount > 0 || messageCount > 0 || deliveringCount > 0 || scheduledCount > 0;
    }

    private static String str(JsonNode row, String field) {
        JsonNode v = row.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static long num(JsonNode row, String field) {
        JsonNode v = row.get(field);
        if (v == null || v.isNull()) {
            return 0L;
        }
        try {
            return Long.parseLong(v.asText().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean flag(JsonNode row, String field) {
        JsonNode v = row.get(field);
        return v != null && !v.isNull() && Boolean.parseBoolean(v.asText());
    }
}
