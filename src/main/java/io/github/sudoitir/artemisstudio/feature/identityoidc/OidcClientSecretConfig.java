package io.github.sudoitir.artemisstudio.feature.identityoidc;

import io.github.sudoitir.artemisstudio.kernel.security.KeyProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OidcClientSecretConfig {

    @Bean
    static BeanPostProcessor oidcClientSecretSource(ObjectProvider<KeyProvider> provider) {
        return new OidcClientSecretSource(provider::getObject);
    }
}
