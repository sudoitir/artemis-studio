package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.feature.alerting.NoticeMessage.Fact;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A queued notice as every sender reads it: what {@link OutboundNoticeService} stored, with the link already
 * absolute or null. A payload that is not a notice is an alert's.
 */
record NoticePayload(String source, String title, String summary, String severity, List<Fact> facts, String url) {

    /** The notice in {@code payloadJson}, or null when it is an alert's payload. */
    static NoticePayload parseOrNull(String payloadJson, ObjectMapper mapper) {
        JsonNode root = mapper.readTree(payloadJson);
        if (!OutboundNoticeService.TYPE.equals(text(root, "type"))) {
            return null;
        }
        List<Fact> facts = new ArrayList<>();
        for (JsonNode f : root.path("facts")) {
            facts.add(new Fact(text(f, "label"), text(f, "value")));
        }
        return new NoticePayload(
                text(root, "source"),
                text(root, "title"),
                text(root, "summary"),
                text(root, "severity"),
                List.copyOf(facts),
                text(root, "url"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
