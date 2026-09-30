package io.github.sudoitir.artemisstudio.kernel.security;

/** The account must enrol a second factor before anything else (ADR-0142). Mapped to HTTP 423. */
public class MfaEnrolmentRequiredException extends RuntimeException {

    public MfaEnrolmentRequiredException() {
        super("Your role requires two-step verification. Set up an authenticator app to continue.");
    }
}
