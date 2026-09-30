package io.github.sudoitir.artemisstudio.feature.identityoidc;

import io.github.sudoitir.artemisstudio.kernel.security.KeyProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.util.StringUtils;

/**
 * Gives every OIDC client registration the client secret held by the {@link KeyProvider}. A secret set in
 * {@code spring.security.oauth2.client.registration.*} is an error unless the provider is {@code env}, whose secret
 * is that same environment variable, whether or not the provider holds one: a secret has exactly one source.
 */
class OidcClientSecretSource implements BeanPostProcessor {

    static final String SECRET = "oidc-client-secret";

    private final Supplier<KeyProvider> provider;

    OidcClientSecretSource(Supplier<KeyProvider> provider) {
        this.provider = provider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof InMemoryClientRegistrationRepository registrations)) {
            return bean;
        }
        KeyProvider keys = provider.get();
        String secret = keys.secret(SECRET).orElse(null);
        List<ClientRegistration> resolved = new ArrayList<>();
        for (ClientRegistration registration : registrations) {
            if (StringUtils.hasText(registration.getClientSecret()) && !"env".equals(keys.name())) {
                throw new IllegalStateException("The OIDC client secret of registration '"
                        + registration.getRegistrationId() + "' is set in spring.security.oauth2.client.registration "
                        + "and the secret provider '" + keys.name() + "' is not 'env'. A client secret has "
                        + "exactly one source: remove it from the Spring properties and keep it in the provider.");
            }
            resolved.add(
                    secret == null
                            ? registration
                            : ClientRegistration.withClientRegistration(registration)
                                    .clientSecret(secret)
                                    .build());
        }
        return new InMemoryClientRegistrationRepository(resolved);
    }
}
