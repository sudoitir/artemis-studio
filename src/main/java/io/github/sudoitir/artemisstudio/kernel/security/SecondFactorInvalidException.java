package io.github.sudoitir.artemisstudio.kernel.security;

/** The second factor presented is wrong or was already used (ADR-0142). Mapped to HTTP 401. */
public class SecondFactorInvalidException extends RuntimeException {

    public SecondFactorInvalidException() {
        super("That code is not valid. Try the current code from your app, or a recovery code.");
    }
}
