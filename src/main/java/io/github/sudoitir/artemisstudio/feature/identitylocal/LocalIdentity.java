package io.github.sudoitir.artemisstudio.feature.identitylocal;

import io.github.sudoitir.artemisstudio.kernel.security.CredentialIdentityProvider;
import io.github.sudoitir.artemisstudio.kernel.security.GrantLoader;
import io.github.sudoitir.artemisstudio.kernel.security.IdentityProviders;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts.Account;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * The {@code local} credential provider: accounts whose passwords Studio stores, hashed, in
 * {@code app_user} (identity-and-sessions spec).
 */
@Component
class LocalIdentity implements IdentityProviders, CredentialIdentityProvider {

    private final UserAccounts accounts;
    private final PasswordEncoder passwordEncoder;
    private final GrantLoader grantLoader;
    /** A hash from the same encoder, checked against when there is no real one. */
    private final String dummyHash;

    LocalIdentity(UserAccounts accounts, PasswordEncoder passwordEncoder, GrantLoader grantLoader) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.grantLoader = grantLoader;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    public List<LocalIdentity> providers() {
        return List.of(this);
    }

    @Override
    public String id() {
        return IdentityLocalModule.PROVIDER_ID;
    }

    @Override
    public String label() {
        return "Password";
    }

    @Override
    public Optional<StudioPrincipal> authenticate(String username, String password) {
        Account user = accounts.byUsername(username).orElse(null);
        boolean hasPassword = user != null && user.passwordHash() != null;
        // Exactly one hash check whether or not the account exists, so the time taken does not
        // say which usernames do.
        boolean matches = passwordEncoder.matches(password, hasPassword ? user.passwordHash() : dummyHash);
        if (!hasPassword || !matches) {
            return Optional.empty();
        }
        if (user.disabled()) {
            throw new DisabledException("Account disabled");
        }
        return Optional.of(new StudioPrincipal(
                user.id(), user.username(), grantLoader.loadFor(user.id()), user.mustChangePassword()));
    }
}
