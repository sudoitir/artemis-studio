package io.github.sudoitir.artemisstudio.kernel.security;

/** The half-finished sign-in a second factor was meant to complete is gone or too old (ADR-0142). Mapped to HTTP 401. */
public class SignInExpiredException extends RuntimeException {

    public SignInExpiredException() {
        super("Your sign-in timed out. Enter your password again.");
    }
}
