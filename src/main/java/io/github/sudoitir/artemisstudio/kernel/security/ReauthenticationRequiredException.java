package io.github.sudoitir.artemisstudio.kernel.security;

/**
 * The action needs a sign-in or step-up within {@link SessionAuthentication#REAUTHENTICATION_WINDOW}
 * (ADR-0103). Answered {@code 403 reauthentication-required}, not 401: the caller is still signed in,
 * and a client treats 401 as "sign in again from scratch".
 */
public class ReauthenticationRequiredException extends RuntimeException {

    public ReauthenticationRequiredException() {
        super("Confirm it is you: re-enter your password or sign in with your provider again, then retry.");
    }
}
