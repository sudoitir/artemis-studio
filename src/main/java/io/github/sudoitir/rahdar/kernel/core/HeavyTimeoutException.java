package io.github.sudoitir.rahdar.kernel.core;

import java.time.Duration;

/**
 * A heavy read the database stopped at its time bound, before it had a page to return. Mapped to HTTP
 * 503 naming the bound, so the caller narrows the request rather than retrying it unchanged. A request
 * that can return what it has read, such as an export, reports the bound in its response instead.
 */
public class HeavyTimeoutException extends RuntimeException {

    private final String endpoint;
    private final Duration limit;

    HeavyTimeoutException(String endpoint, Duration limit) {
        super("%s stopped at their time bound of %d seconds. Narrow the time range or the filters and try again."
                .formatted(capitalised(endpoint), limit.toSeconds()));
        this.endpoint = endpoint;
        this.limit = limit;
    }

    private static String capitalised(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** What was stopped. */
    public String endpoint() {
        return endpoint;
    }

    /** The time bound it ran into. */
    public Duration limit() {
        return limit;
    }
}
