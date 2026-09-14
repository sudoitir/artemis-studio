package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.Optional;
import org.springframework.security.authentication.DisabledException;

/** Username and password, checked by the provider (identity-and-sessions spec). */
public non-sealed interface CredentialIdentityProvider extends IdentityProvider {

    /**
     * The principal these credentials identify, or empty when they do not match. The kernel's
     * login path owns throttling, the audit row and the session; a provider only answers.
     *
     * @throws DisabledException when the account exists but is disabled
     */
    Optional<StudioPrincipal> authenticate(String username, String password);
}
