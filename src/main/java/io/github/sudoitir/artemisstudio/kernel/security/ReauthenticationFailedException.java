package io.github.sudoitir.artemisstudio.kernel.security;

/** A step-up that did not prove the caller is the signed-in user (ADR-0103). */
public class ReauthenticationFailedException extends RuntimeException {

    public ReauthenticationFailedException(String message) {
        super(message);
    }
}
