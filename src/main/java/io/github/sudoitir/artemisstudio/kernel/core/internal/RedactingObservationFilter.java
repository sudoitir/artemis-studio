package io.github.sudoitir.artemisstudio.kernel.core.internal;

import io.github.sudoitir.artemisstudio.kernel.core.SecretRedactor;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Component;

/**
 * Redacts every observation before it becomes a metric tag or a span attribute (ADR-0133, ADR-0147): a value goes
 * through {@link SecretRedactor} (and is masked whole under a credential-like key), a key that names message content
 * is dropped, and the error, which becomes the span's exception event, is copied with its messages redacted.
 */
@Component
class RedactingObservationFilter implements ObservationFilter {

    private static final int CAUSE_DEPTH = 8;

    /** {@code body}, {@code payload} and {@code headers} as a whole word of a key, and {@code message.content}. */
    private static final Pattern CONTENT_KEY =
            Pattern.compile("(?i)(^|[._-])(body|payload|headers?)($|[._-])|message\\.content");

    @Override
    public Observation.Context map(Observation.Context context) {
        StreamSupport.stream(context.getLowCardinalityKeyValues().spliterator(), false)
                .toList()
                .forEach(kv -> {
                    context.removeLowCardinalityKeyValue(kv.getKey());
                    KeyValue safe = redacted(kv);
                    if (safe != null) {
                        context.addLowCardinalityKeyValue(safe);
                    }
                });
        StreamSupport.stream(context.getHighCardinalityKeyValues().spliterator(), false)
                .toList()
                .forEach(kv -> {
                    context.removeHighCardinalityKeyValue(kv.getKey());
                    KeyValue safe = redacted(kv);
                    if (safe != null) {
                        context.addHighCardinalityKeyValue(safe);
                    }
                });
        if (context.getError() != null) {
            context.setError(redacted(context.getError(), 0));
        }
        return context;
    }

    /** The key-value to keep, or {@code null} for one that names content. */
    private static KeyValue redacted(KeyValue kv) {
        if (CONTENT_KEY.matcher(kv.getKey()).find()) {
            return null;
        }
        String value = SecretRedactor.isCredentialKey(kv.getKey())
                ? SecretRedactor.MASK
                : SecretRedactor.redact(kv.getValue());
        return Objects.equals(value, kv.getValue()) ? kv : KeyValue.of(kv.getKey(), value);
    }

    /** The throwable itself when nothing in its chain needs redacting, else a copy that carries the redacted text. */
    private static Throwable redacted(Throwable error, int depth) {
        Throwable cause = error.getCause();
        Throwable safeCause = cause == null || depth >= CAUSE_DEPTH ? cause : redacted(cause, depth + 1);
        String message = error.getMessage();
        String safeMessage = SecretRedactor.redact(message);
        if (Objects.equals(message, safeMessage) && safeCause == cause) {
            return error;
        }
        RedactedError copy = new RedactedError(error.getClass().getName() + ": " + safeMessage, safeCause);
        copy.setStackTrace(error.getStackTrace());
        return copy;
    }

    /** Stands in for an error whose message held a credential; its message names the original type. */
    static final class RedactedError extends RuntimeException {
        RedactedError(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
