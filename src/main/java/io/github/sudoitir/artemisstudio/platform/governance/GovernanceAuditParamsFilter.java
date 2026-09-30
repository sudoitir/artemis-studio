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
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The content policy over audit parameters (audit-log spec): a literal compared with a classified
 * header or property is redacted, and anything the detectors find is masked. Covers SQL console text
 * ({@code props->>'email' = '...'}) and selector filters ({@code email = '...'}).
 */
@Component
@Order(0)
@RequiredArgsConstructor
class GovernanceAuditParamsFilter implements AuditParamsFilter {

    static final String REDACTED_LITERAL = "'[redacted]'";

    /** The operator and the quoted literal that follow a compared name; the literal is group 2. */
    private static final String COMPARED_TO_LITERAL = "\\s*(?:=|<>|!=|\\bLIKE\\b)\\s*('[^']*+(?:''[^']*+)*')";

    /** {@code props->>'name'} compared with a quoted literal; the name is group 1. */
    private static final Pattern PROPS_COMPARISON =
            Pattern.compile("(?i)props\\s*->>?\\s*'([^']+)'" + COMPARED_TO_LITERAL);

    /** A bare name compared with a quoted literal; the name is group 1. */
    private static final Pattern NAME_COMPARISON = Pattern.compile("(?i)\\b([a-z_][\\w$.-]*)" + COMPARED_TO_LITERAL);

    private final ContentPolicy policy;

    @Override
    public Map<String, Object> filter(Map<String, ?> params) {
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
        return policy.governText(redactComparisons(NAME_COMPARISON, redactComparisons(PROPS_COMPARISON, text)));
    }

    private String redactComparisons(Pattern comparison, String text) {
        Matcher m = comparison.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(
                    out,
                    Matcher.quoteReplacement(
                            classified(m.group(1))
                                    ? text.substring(m.start(), m.start(2)) + REDACTED_LITERAL
                                    : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private boolean classified(String name) {
        // The SQL console addresses a property as props.<name>; a selector names it bare.
        String bare = name.regionMatches(true, 0, "props.", 0, 6) ? name.substring(6) : name;
        return policy.classifies(Location.PROPERTY, bare) || policy.classifies(Location.HEADER, bare);
    }
}
