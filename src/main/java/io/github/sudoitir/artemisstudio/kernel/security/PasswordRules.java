package io.github.sudoitir.artemisstudio.kernel.security;

/**
 * The rules a new local password must meet, implemented by the module that owns local accounts so
 * the kernel can apply them when an administrator creates one without depending on it.
 */
public interface PasswordRules {

    /**
     * @throws PasswordPolicyException with a reason the user can act on, when the password is not
     *     acceptable
     */
    void check(String username, String password);
}
