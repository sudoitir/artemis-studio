package io.github.sudoitir.artemisstudio.kernel.approval;

import org.springframework.http.HttpStatus;

/**
 * A decision on a held request was refused by Studio's rules or the provider's (ADR-0181). It was audited and
 * recorded on the request's timeline before this was thrown.
 */
public class VoteRefusedException extends RuntimeException {

    private final HttpStatus status;
    private final String slug;

    VoteRefusedException(HttpStatus status, String slug, String message) {
        super(message);
        this.status = status;
        this.slug = slug;
    }

    public HttpStatus status() {
        return status;
    }

    public String slug() {
        return slug;
    }
}
