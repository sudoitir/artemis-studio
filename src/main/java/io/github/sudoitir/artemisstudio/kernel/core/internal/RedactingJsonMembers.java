package io.github.sudoitir.artemisstudio.kernel.core.internal;

import io.github.sudoitir.artemisstudio.kernel.core.SecretRedactor;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.json.JsonWriter.ValueProcessor;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks every string member of a structured log line (message, stack trace, MDC values), because Boot's structured
 * formats bypass the Logback pattern. Enabled by {@code logging.structured.json.customizer} in {@code application.yml}.
 */
public class RedactingJsonMembers implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(ValueProcessor.of(String.class, SecretRedactor::redact));
    }
}
