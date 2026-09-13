package io.github.sudoitir.artemisstudio.kernel.core;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * RFC 9457 problem details with a stable {@code type} URI, so the frontend switches
 * on the class of failure rather than string-matching a message. Every module's
 * exception advice builds its problems here, so the type namespace has one owner.
 */
public final class Problems {

    public static final String TYPE_BASE = "https://artemis-studio.dev/problems/";

    public static ProblemDetail of(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + slug));
        problem.setTitle(title);
        return problem;
    }

    private Problems() {}
}
