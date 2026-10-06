package io.github.sudoitir.artemisstudio.kernel.core;

/** A cluster or node id that does not exist. Mapped to HTTP 404 by the web layer. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String what, Object id) {
        super(what + " " + id + " does not exist.");
    }

    /** A not-found with its own message, for one that must not name what was asked for. */
    public NotFoundException(String message) {
        super(message);
    }
}
