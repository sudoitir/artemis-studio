package io.github.sudoitir.artemisstudio.feature.transfer;

import org.springframework.http.HttpStatus;

/** A transfer request refused before anything was done, with the problem type it answers as. */
public class TransferRefusedException extends RuntimeException {

    private final HttpStatus status;
    private final String slug;

    public TransferRefusedException(HttpStatus status, String slug, String message) {
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
