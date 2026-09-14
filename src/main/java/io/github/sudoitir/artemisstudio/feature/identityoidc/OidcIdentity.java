package io.github.sudoitir.artemisstudio.feature.identityoidc;

import io.github.sudoitir.artemisstudio.kernel.security.IdentityProviders;
import io.github.sudoitir.artemisstudio.kernel.security.RedirectIdentityProvider;
import java.util.List;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * One redirect provider per configured OpenID Connect client registration (ADR-0040). Opt-in:
 * with no {@code spring.security.oauth2.client.registration.*}, Boot creates no repository, so
 * there are no providers and nothing is added to the security chain.
 */
@Component
@RequiredArgsConstructor
class OidcIdentity implements IdentityProviders {

    private final ObjectProvider<ClientRegistrationRepository> registrations;
    private final OidcAuthenticationSuccessHandler successHandler;

    record Registration(String id, String label, String startPath) implements RedirectIdentityProvider {}

    /**
     * {@link ClientRegistrationRepository} has no listing method, so registrations are only
     * discoverable when the instance is also {@link Iterable} — true of Boot's
     * {@code InMemoryClientRegistrationRepository}, the only kind this application creates.
     */
    @Override
    public List<Registration> providers() {
        if (!(registrations.getIfAvailable() instanceof Iterable<?> all)) {
            return List.of();
        }
        return StreamSupport.stream(all.spliterator(), false)
                .map(ClientRegistration.class::cast)
                .map(r -> new Registration(
                        r.getRegistrationId(), r.getClientName(), "/oauth2/authorization/" + r.getRegistrationId()))
                .toList();
    }

    @Override
    public void configure(HttpSecurity http) throws Exception {
        if (registrations.getIfAvailable() != null) {
            http.oauth2Login(oauth2 -> oauth2.successHandler(successHandler));
        }
    }
}
