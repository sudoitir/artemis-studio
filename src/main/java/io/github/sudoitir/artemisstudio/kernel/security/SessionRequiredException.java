package io.github.sudoitir.artemisstudio.kernel.security;

/**
 * The action is for a signed-in browser session, and the caller authenticated with a bearer token
 * (ADR-0142): a token cannot mint tokens, or change second factors. Answered {@code 403 session-required}.
 */
public class SessionRequiredException extends RuntimeException {

    public SessionRequiredException(String message) {
        super(message);
    }
}
