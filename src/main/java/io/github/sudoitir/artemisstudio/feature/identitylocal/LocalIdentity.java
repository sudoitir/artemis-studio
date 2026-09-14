package io.github.sudoitir.artemisstudio.feature.identitylocal;

import io.github.sudoitir.artemisstudio.kernel.security.CredentialIdentityProvider;
import io.github.sudoitir.artemisstudio.kernel.security.GrantLoader;
import io.github.sudoitir.artemisstudio.kernel.security.IdentityProviders;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts.Account;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * The {@code local} credential provider: accounts whose passwords Studio stores, hashed, in
 * {@code app_user} (identity-and-sessions spec).
 */
@Component
@RequiredArgsConstructor
class LocalIdentity implements IdentityProviders, CredentialIdentityProvider {

    private final UserAccounts accounts;
    private final PasswordEncoder passwordEncoder;
    private final GrantLoader grantLoader;

    @Override
    public List<LocalIdentity> providers() {
        return List.of(this);
    }

    @Override
    public String id() {
        return "local";
    }

    @Override
    public String label() {
        return "Password";
    }

    @Override
    public Optional<StudioPrincipal> authenticate(String username, String password) {
        Account user = accounts.byUsername(username).orElse(null);
        if (user == null
                || user.disabled()
                || user.passwordHash() == null
                || !passwordEncoder.matches(password, user.passwordHash())) {
            if (user != null && user.disabled()) {
                throw new DisabledException("Account disabled");
            }
            return Optional.empty();
        }
        return Optional.of(new StudioPrincipal(
                user.id(), user.username(), grantLoader.loadFor(user.id()), user.mustChangePassword()));
    }
}
