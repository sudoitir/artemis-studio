package io.github.sudoitir.artemisstudio.domain.alerting;

import tools.jackson.databind.ObjectMapper;

/**
 * {@code alert_rule.scope} ({@code {addressPattern, queuePattern, node}}).
 * A pattern with no {@code *} must match exactly; {@code *} is a wildcard
 * translated to a regex — enough for "starts with", "ends with", "contains"
 * without pulling in a glob library for three field-level filters.
 */
public record AlertScope(String addressPattern, String queuePattern, String node) {

    public static final AlertScope NONE = new AlertScope(null, null, null);

    public static AlertScope parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return NONE;
        }
        try {
            return mapper.readValue(json, AlertScope.class);
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    public boolean matchesAddress(String address) {
        return matches(addressPattern, address);
    }

    public boolean matchesQueue(String queueName) {
        return matches(queuePattern, queueName);
    }

    public boolean matchesNode(String artemisNodeId) {
        return node == null || node.isBlank() || node.equals(artemisNodeId);
    }

    private static boolean matches(String pattern, String value) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        if (!pattern.contains("*")) {
            return pattern.equals(value);
        }
        String regex = "\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E";
        return value != null && value.matches(regex);
    }
}
