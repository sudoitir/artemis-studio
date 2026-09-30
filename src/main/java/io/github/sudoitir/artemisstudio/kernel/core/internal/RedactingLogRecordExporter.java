package io.github.sudoitir.artemisstudio.kernel.core.internal;

import io.github.sudoitir.artemisstudio.kernel.core.SecretRedactor;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.common.ValueType;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.logs.data.Body;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.resources.Resource;
import java.util.Collection;
import java.util.List;

/**
 * Redacts each OTel log record on its way to the exporter (ADR-0133, ADR-0147): the body, every string attribute
 * (which includes the exception message and stack trace). The appender reads the formatted message itself, so
 * Logback's redacting converters never see it, and the SDK's processors cannot change a body, so this sits on the
 * exporter, the last point every record passes.
 */
final class RedactingLogRecordExporter implements LogRecordExporter {

    private final LogRecordExporter delegate;

    RedactingLogRecordExporter(LogRecordExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<LogRecordData> logs) {
        return delegate.export(
                logs.stream().<LogRecordData>map(RedactedLogRecord::new).toList());
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Attributes redacted(Attributes attributes) {
        AttributesBuilder out = Attributes.builder();
        attributes.forEach((key, value) -> {
            if (key.getType() == AttributeType.STRING) {
                out.put(
                        (AttributeKey<String>) key,
                        SecretRedactor.isCredentialKey(key.getKey())
                                ? SecretRedactor.MASK
                                : SecretRedactor.redact((String) value));
            } else if (key.getType() == AttributeType.STRING_ARRAY) {
                out.put(
                        (AttributeKey<List<String>>) key,
                        ((List<String>) value)
                                .stream().map(SecretRedactor::redact).toList());
            } else {
                out.put((AttributeKey) key, value);
            }
        });
        return out.build();
    }

    private record RedactedLogRecord(LogRecordData delegate) implements LogRecordData {

        @Override
        public Resource getResource() {
            return delegate.getResource();
        }

        @Override
        public InstrumentationScopeInfo getInstrumentationScopeInfo() {
            return delegate.getInstrumentationScopeInfo();
        }

        @Override
        public long getTimestampEpochNanos() {
            return delegate.getTimestampEpochNanos();
        }

        @Override
        public long getObservedTimestampEpochNanos() {
            return delegate.getObservedTimestampEpochNanos();
        }

        @Override
        public SpanContext getSpanContext() {
            return delegate.getSpanContext();
        }

        @Override
        public Severity getSeverity() {
            return delegate.getSeverity();
        }

        @Override
        public String getSeverityText() {
            return delegate.getSeverityText();
        }

        @Override
        @SuppressWarnings("deprecation")
        public Body getBody() {
            Body body = delegate.getBody();
            return body.getType() == Body.Type.STRING ? Body.string(SecretRedactor.redact(body.asString())) : body;
        }

        @Override
        public Value<?> getBodyValue() {
            Value<?> body = delegate.getBodyValue();
            return body != null && body.getType() == ValueType.STRING
                    ? Value.of(SecretRedactor.redact((String) body.getValue()))
                    : body;
        }

        @Override
        public Attributes getAttributes() {
            return redacted(delegate.getAttributes());
        }

        @Override
        public int getTotalAttributeCount() {
            return delegate.getTotalAttributeCount();
        }

        @Override
        public String getEventName() {
            return delegate.getEventName();
        }
    }
}
