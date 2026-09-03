package io.github.sudoitir.artemisstudio.service;

/** A cluster or node id that does not exist. Mapped to HTTP 404 by the web layer. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String what, Object id) {
        super(what + " " + id + " does not exist.");
    }
}
