package io.github.sudoitir.artemisstudio.feature.alerting;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.HttpHeaders;

/**
 * A receiver's {@code Retry-After}, in either form RFC 9110 allows: delta-seconds or an HTTP
 * date. Anything else is ignored and the default backoff applies.
 */
final class RetryAfter {

    private RetryAfter() {}

    static Duration parse(HttpHeaders headers) {
        List<String> values = headers != null ? headers.get(HttpHeaders.RETRY_AFTER) : null;
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.get(0).trim();
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value)));
        } catch (NumberFormatException notSeconds) {
            try {
                Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                Duration wait = Duration.between(Instant.now(), at);
                return wait.isNegative() ? Duration.ZERO : wait;
            } catch (RuntimeException notADate) {
                return null;
            }
        }
    }
}
