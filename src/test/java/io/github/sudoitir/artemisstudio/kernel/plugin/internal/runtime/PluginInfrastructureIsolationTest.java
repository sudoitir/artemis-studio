package io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ApplicationContext;

/**
 * The configuration a plugin context is built from must never reach the main application: a
 * scanned {@code @EnableWebMvc} switches off Boot's MVC auto-configuration for every core
 * endpoint, and a scanned converter re-wires the host's JSON (ADR-0099).
 */
class PluginInfrastructureIsolationTest extends PostgresIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void thePluginContextsConfigurationIsNotPartOfTheApplication() {
        assertThat(context.getBeanNamesForType(PluginInfrastructure.class)).isEmpty();
        assertThat(context.getBeanNamesForType(PluginMessageConverterConfig.class))
                .isEmpty();
    }

    @Test
    void bootsOwnMvcConfigurationIsStillInCharge() {
        assertThat(context.getBeanNamesForType(WebMvcAutoConfiguration.class)).isNotEmpty();
    }
}
