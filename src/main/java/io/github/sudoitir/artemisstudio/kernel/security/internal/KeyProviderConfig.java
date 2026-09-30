package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.security.KeyProvider;
import io.github.sudoitir.artemisstudio.kernel.security.SecretProviderProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Selects the one {@link KeyProvider} named by {@code artemis-studio.secrets.provider}. */
@Configuration(proxyBeanMethods = false)
class KeyProviderConfig {

    @Bean
    KeyProvider keyProvider(SecretProviderProperties properties, Environment environment) {
        return switch (properties.provider()) {
            case "env" -> new EnvKeyProvider(environment);
            case "file" -> new FileKeyProvider(properties.file().directory());
            case "vault" -> new VaultKeyProvider(properties.vault());
            case "kubernetes" -> new KubernetesKeyProvider(properties.kubernetes());
            default ->
                throw new IllegalStateException("Unknown artemis-studio.secrets.provider '" + properties.provider()
                        + "'; expected env, file, vault or kubernetes.");
        };
    }
}
