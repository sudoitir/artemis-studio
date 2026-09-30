package io.github.sudoitir.artemisstudio.kernel.security;

/** A new password broke the password policy. The message is the reason, written for the user. Mapped to HTTP 400. */
public class PasswordPolicyException extends RuntimeException {

    public PasswordPolicyException(String reason) {
        super(reason);
    }
}
