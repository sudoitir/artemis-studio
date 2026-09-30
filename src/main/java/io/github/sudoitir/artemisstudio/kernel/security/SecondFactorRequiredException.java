package io.github.sudoitir.artemisstudio.kernel.security;

/**
 * The action needs a session that has verified a second factor, and this one has not (ADR-0143):
 * minting a token as a user who must hold a factor, or resetting the factors of such a user. Answered
 * {@code 403 mfa-required}, and the message says what to do.
 */
public class SecondFactorRequiredException extends RuntimeException {

    public SecondFactorRequiredException(String message) {
        super(message);
    }
}
