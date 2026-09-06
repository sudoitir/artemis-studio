package io.github.sudoitir.artemisstudio.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link JdbcConfigPropertySourceLocator} into the bootstrap context.
 * Referenced from {@code META-INF/spring.factories} under
 * {@code org.springframework.cloud.bootstrap.BootstrapConfiguration} — the
 * bootstrap context is created before component scanning, so a {@code @Component}
 * on the locator would never be seen.
 */
@Configuration(proxyBeanMethods = false)
public class JdbcConfigPropertySourceConfiguration {

    @Bean
    public JdbcConfigPropertySourceLocator jdbcConfigPropertySourceLocator() {
        return new JdbcConfigPropertySourceLocator();
    }
}
