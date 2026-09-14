package io.github.sudoitir.artemisstudio.kernel.security;

/** Too many failed logins for this username/source in a short period. Mapped to HTTP 429. */
public class LoginThrottledException extends RuntimeException {

    public LoginThrottledException() {
        super("Too many failed login attempts. Try again later.");
    }
}
