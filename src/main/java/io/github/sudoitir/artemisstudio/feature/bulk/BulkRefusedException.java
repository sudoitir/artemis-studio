package io.github.sudoitir.artemisstudio.feature.bulk;

import org.springframework.http.HttpStatus;

/** A bulk request refused before anything was done, with the problem type it answers as. */
public class BulkRefusedException extends RuntimeException {

    private final HttpStatus status;
    private final String slug;

    public BulkRefusedException(HttpStatus status, String slug, String message) {
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
