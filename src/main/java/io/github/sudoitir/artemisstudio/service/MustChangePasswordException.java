package io.github.sudoitir.artemisstudio.service;

/** The account must change its password before anything else. Mapped to HTTP 423. */
public class MustChangePasswordException extends RuntimeException {

    public MustChangePasswordException() {
        super("This account must change its password before continuing.");
    }
}
