package io.github.sudoitir.artemisstudio.kernel.core.internal;

import io.github.sudoitir.artemisstudio.kernel.core.SecretRedactor;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;

/**
 * Redacts each span on its way to the exporter: its string attributes, its events (an exception event carries the
 * error's message and stack trace) and its status description. See {@link RedactingExporters}.
 */
final class RedactingSpanExporter implements SpanExporter {

    private final SpanExporter delegate;

    RedactingSpanExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        return delegate.export(spans.stream().<SpanData>map(RedactedSpan::new).toList());
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    private static final class RedactedSpan extends DelegatingSpanData {

        RedactedSpan(SpanData delegate) {
            super(delegate);
        }

        @Override
        public Attributes getAttributes() {
            return RedactingLogRecordExporter.redacted(super.getAttributes());
        }

        @Override
        public List<EventData> getEvents() {
            return super.getEvents().stream()
                    .map(event -> EventData.create(
                            event.getEpochNanos(),
                            event.getName(),
                            RedactingLogRecordExporter.redacted(event.getAttributes()),
                            event.getTotalAttributeCount()))
                    .toList();
        }

        @Override
        public StatusData getStatus() {
            StatusData status = super.getStatus();
            return StatusData.create(status.getStatusCode(), SecretRedactor.redact(status.getDescription()));
        }
    }
}
