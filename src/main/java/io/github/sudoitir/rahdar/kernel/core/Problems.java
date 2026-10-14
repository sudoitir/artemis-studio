package io.github.sudoitir.rahdar.kernel.core;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * RFC 9457 problem details with a stable {@code type} URI, so the frontend switches
 * on the class of failure rather than string-matching a message. Every module's
 * exception advice builds its problems here, so the type namespace has one owner. Detail and title are passed through
 * {@link SecretRedactor}, so no advice can echo a credential.
 */
public final class Problems {

    public static final String TYPE_BASE = "https://rahdar.dev/problems/";

    public static ProblemDetail of(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, SecretRedactor.redact(detail));
        problem.setType(URI.create(TYPE_BASE + slug));
        problem.setTitle(SecretRedactor.redact(title));
        return problem;
    }

    /** Seconds a client is told to wait before retrying a refused heavy request; a running request has no known end. */
    public static final int HEAVY_RETRY_AFTER_SECONDS = 5;

    /**
     * A heavy request refused because a limit is full. The wait is in the body as well as the
     * {@code Retry-After} header, because an event stream reports it as a frame inside a 200.
     */
    public static ProblemDetail heavyLimit(HeavyLimitException e) {
        ProblemDetail problem = of(
                HttpStatus.TOO_MANY_REQUESTS,
                "too-many-heavy-requests",
                "Too many heavy requests at once",
                e.getMessage());
        problem.setProperty("endpoint", e.endpoint());
        problem.setProperty("cap", e.cap());
        problem.setProperty("installation", e.installation());
        problem.setProperty("retryAfter", HEAVY_RETRY_AFTER_SECONDS);
        return problem;
    }

    /** A heavy read stopped at its time bound; the bound is named in the body, as in a bounded response. */
    public static ProblemDetail heavyTimeout(HeavyTimeoutException e) {
        ProblemDetail problem = of(
                HttpStatus.SERVICE_UNAVAILABLE,
                "heavy-request-timed-out",
                "The request reached its time bound",
                e.getMessage());
        problem.setProperty("endpoint", e.endpoint());
        problem.setProperty("bound", HeavyRequests.Bound.TIME);
        problem.setProperty("seconds", e.limit().toSeconds());
        return problem;
    }

    private Problems() {}
}
