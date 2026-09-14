package io.github.sudoitir.artemisstudio.platform.governance;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditParamsFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The content policy over audit parameters (audit-log spec): a literal compared with a classified
 * header or property is redacted, and anything the detectors find is masked. Covers SQL console text
 * ({@code props->>'email' = '...'}) and selector filters ({@code email = '...'}).
 */
@Component
@RequiredArgsConstructor
class GovernanceAuditParamsFilter implements AuditParamsFilter {

    static final String REDACTED_LITERAL = "'[redacted]'";

    /** A name, or {@code props->>'name'}, compared with a quoted literal. */
    private static final Pattern COMPARISON = Pattern.compile(
            "(?i)(?:props\\s*->>?\\s*'([^']+)'|\\b([A-Za-z_][\\w$.-]*))\\s*(?:=|<>|!=|\\bLIKE\\b)\\s*('(?:[^']|'')*')");

    private final ContentPolicy policy;

    @Override
    public Map<String, ?> filter(Map<String, ?> params) {
        Map<String, Object> out = new LinkedHashMap<>();
        params.forEach((key, value) -> out.put(key, value(value)));
        return out;
    }

    private Object value(Object value) {
        if (value instanceof String text) {
            return text(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(k, value(v)));
            return out;
        }
        if (value instanceof Collection<?> items) {
            List<Object> out = new ArrayList<>(items.size());
            items.forEach(item -> out.add(value(item)));
            return out;
        }
        return value;
    }

    String text(String text) {
        Matcher m = COMPARISON.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            String replacement =
                    classified(name) ? text.substring(m.start(), m.start(3)) + REDACTED_LITERAL : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return policy.governText(out.toString());
    }

    private boolean classified(String name) {
        return policy.classifies(Location.PROPERTY, name) || policy.classifies(Location.HEADER, name);
    }
}
