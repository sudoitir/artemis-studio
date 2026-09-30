package io.github.sudoitir.artemisstudio.kernel.audit;

import io.github.sudoitir.artemisstudio.kernel.core.SecretRedactor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Masks credentials in audit parameters: a value under a credential-like name is replaced, and
 * every string is passed through {@link SecretRedactor}. Runs after the content policy.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class CredentialAuditParamsFilter implements AuditParamsFilter {

    @Override
    public Map<String, ?> filter(Map<String, ?> params) {
        Map<String, Object> out = new LinkedHashMap<>();
        params.forEach(
                (key, value) -> out.put(key, SecretRedactor.isCredentialKey(key) ? SecretRedactor.MASK : value(value)));
        return out;
    }

    private Object value(Object value) {
        if (value instanceof String text) {
            return SecretRedactor.redact(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(
                    k,
                    k instanceof String name && SecretRedactor.isCredentialKey(name) ? SecretRedactor.MASK : value(v)));
            return out;
        }
        if (value instanceof Collection<?> items) {
            List<Object> out = new ArrayList<>(items.size());
            items.forEach(item -> out.add(value(item)));
            return out;
        }
        return value;
    }
}
