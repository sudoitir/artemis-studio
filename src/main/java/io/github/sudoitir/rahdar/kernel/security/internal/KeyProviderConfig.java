package io.github.sudoitir.rahdar.kernel.security.internal;

import io.github.sudoitir.rahdar.kernel.security.KeyProvider;
import io.github.sudoitir.rahdar.kernel.security.SecretProviderProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Selects the one {@link KeyProvider} named by {@code rahdar.secrets.provider}. */
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
                throw new IllegalStateException("Unknown rahdar.secrets.provider '" + properties.provider()
                        + "'; expected env, file, vault or kubernetes.");
        };
    }
}
