package io.github.sudoitir.artemisstudio.feature.alerting;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One delivery's payload as every sender reads it (ADR-0105). Parsed leniently: a row queued
 * before payload version 2 has no cluster, labels or times, and still renders — its subject key
 * stands in for the label and the missing facts are left out rather than invented.
 *
 * @param studioUrl a link to the cluster's alerts, or null when no public URL is configured
 */
public record AlertMessage(
        UUID ruleId,
        String ruleName,
        String severity,
        UUID clusterId,
        String clusterName,
        String studioUrl,
        List<Line> transitions) {

    /** One subject that fired or resolved. {@code value} and {@code at} may be null. */
    public record Line(String subject, String label, boolean fired, Double value, Instant at) {}

    public long firedCount() {
        return transitions.stream().filter(Line::fired).count();
    }

    public long resolvedCount() {
        return transitions.size() - firedCount();
    }

    public static AlertMessage parse(String payloadJson, ObjectMapper mapper) {
        JsonNode root = mapper.readTree(payloadJson);
        List<Line> lines = new ArrayList<>();
        for (JsonNode t : root.path("transitions")) {
            String subject = text(t, "subject");
            String label = text(t, "subjectLabel");
            JsonNode value = t.get("value");
            String at = text(t, "at");
            lines.add(new Line(
                    subject,
                    label != null ? label : subject,
                    !"RESOLVED".equals(text(t, "kind")),
                    value != null && value.isNumber() ? value.asDouble() : null,
                    at != null ? parseInstant(at) : null));
        }
        return new AlertMessage(
                uuid(text(root, "ruleId")),
                orElse(text(root, "ruleName"), "Alert"),
                orElse(text(root, "severity"), "INFO"),
                uuid(text(root, "clusterId")),
                text(root, "clusterName"),
                text(root, "studioUrl"),
                List.copyOf(lines));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
