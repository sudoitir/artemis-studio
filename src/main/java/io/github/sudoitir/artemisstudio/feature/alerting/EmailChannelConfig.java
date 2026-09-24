package io.github.sudoitir.artemisstudio.feature.alerting;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * An email channel's non-secret configuration (ADR-0105 D5), as stored in
 * {@code notification_channel.config}. The SMTP password is the channel's secret and is never
 * here.
 *
 * @param security {@code STARTTLS} (required, never opportunistic), {@code TLS} (implicit, e.g.
 *     port 465) or {@code NONE}
 */
public record EmailChannelConfig(
        String host, int port, String security, String username, String from, List<String> to, String subjectPrefix) {

    public static final String STARTTLS = "STARTTLS";
    public static final String TLS = "TLS";
    public static final String NONE = "NONE";

    public static EmailChannelConfig parse(String json, ObjectMapper mapper) {
        JsonNode root = mapper.readTree(json == null || json.isBlank() ? "{}" : json);
        List<String> to = new ArrayList<>();
        JsonNode list = root.get("to");
        if (list != null && list.isArray()) {
            for (JsonNode address : list) {
                if (!address.isNull() && !address.asString().isBlank()) {
                    to.add(address.asString().trim());
                }
            }
        } else if (list != null && list.isString()) {
            for (String address : list.asString().split("[,;]")) {
                if (!address.isBlank()) {
                    to.add(address.trim());
                }
            }
        }
        JsonNode port = root.get("port");
        return new EmailChannelConfig(
                text(root, "host"),
                port == null || port.isNull() ? 0 : port.asInt(0),
                text(root, "security") == null ? STARTTLS : text(root, "security"),
                text(root, "username"),
                text(root, "from"),
                List.copyOf(to),
                text(root, "subjectPrefix"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asString().trim();
        return s.isEmpty() ? null : s;
    }
}
