package io.github.sudoitir.artemisstudio.feature.identityoidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.kernel.security.KeyProvider;
import io.github.sudoitir.artemisstudio.kernel.security.Keyring;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

class OidcClientSecretSourceTest {

    private static KeyProvider provider(String name, String secret) {
        return new KeyProvider() {
            public Keyring load() {
                throw new UnsupportedOperationException();
            }

            public Optional<String> secret(String key) {
                return Optional.ofNullable(secret);
            }

            public String name() {
                return name;
            }
        };
    }

    private static InMemoryClientRegistrationRepository repository(String configuredSecret) {
        return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("studio")
                .clientId("studio")
                .clientSecret(configuredSecret)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/studio")
                .authorizationUri("https://idp/authorize")
                .tokenUri("https://idp/token")
                .build());
    }

    private static String secretOf(Object repository) {
        return ((InMemoryClientRegistrationRepository) repository)
                .findByRegistrationId("studio")
                .getClientSecret();
    }

    @Test
    void theProviderSecretBecomesTheClientSecret() {
        var source = new OidcClientSecretSource(() -> provider("vault", "from-vault"));

        assertThat(secretOf(source.postProcessAfterInitialization(repository(null), "repo")))
                .isEqualTo("from-vault");
    }

    @Test
    void aSecretAlsoConfiguredInSpringPropertiesFailsWhenTheProviderIsNotEnv() {
        var source = new OidcClientSecretSource(() -> provider("kubernetes", "from-k8s"));

        assertThatThrownBy(() -> source.postProcessAfterInitialization(repository("configured"), "repo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one source")
                .hasMessageContaining("kubernetes")
                .hasMessageNotContaining("configured")
                .hasMessageNotContaining("from-k8s");
    }

    @Test
    void theEnvProviderMayRepeatTheConfiguredSecret() {
        var source = new OidcClientSecretSource(() -> provider("env", "same"));

        assertThat(secretOf(source.postProcessAfterInitialization(repository("same"), "repo")))
                .isEqualTo("same");
    }

    @Test
    void aProviderWithoutTheSecretLeavesTheRegistrationAlone() {
        var source = new OidcClientSecretSource(() -> provider("file", null));

        assertThat(secretOf(source.postProcessAfterInitialization(repository("configured"), "repo")))
                .isEqualTo("configured");
    }
}
