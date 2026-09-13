package io.github.sudoitir.artemisstudio.feature.identitylocal;

import io.github.sudoitir.artemisstudio.kernel.security.CredentialIdentityProvider;
import io.github.sudoitir.artemisstudio.kernel.security.IdentityProviders;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.internal.GrantLoader;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
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

    private final AppUserRepository users;
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
        AppUserEntity user = users.findByUsername(username).orElse(null);
        if (user == null
                || user.isDisabled()
                || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            if (user != null && user.isDisabled()) {
                throw new DisabledException("Account disabled");
            }
            return Optional.empty();
        }
        return Optional.of(new StudioPrincipal(
                user.getId(), user.getUsername(), grantLoader.loadFor(user.getId()), user.isMustChangePassword()));
    }
}
