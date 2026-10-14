package io.github.sudoitir.rahdar.kernel.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** {@code rahdar.plugins.trusted-keys} binds from an indexed list in YAML and from indexed environment variables. */
class PluginPropertiesBindingTest {

    private static final String PEM = "-----BEGIN PUBLIC KEY-----\nQUJD\n-----END PUBLIC KEY-----\n";

    private static PluginProperties bind(StandardEnvironment environment) {
        return Binder.get(environment)
                .bind("rahdar.plugins", PluginProperties.class)
                .get();
    }

    @Test
    void bindsAListFromIndexedProperties() {
        var environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "yaml",
                        Map.of(
                                "rahdar.plugins.trusted-keys[0].name", "Example Publisher",
                                "rahdar.plugins.trusted-keys[0].pem", PEM,
                                "rahdar.plugins.trusted-keys[1].name", "Second")));

        assertThat(bind(environment).trustedKeys())
                .containsExactly(
                        new PluginProperties.TrustedKey("Example Publisher", PEM),
                        new PluginProperties.TrustedKey("Second", null));
    }

    @Test
    void bindsAListFromIndexedEnvironmentVariables() {
        var environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of(
                                "RAHDAR_PLUGINS_TRUSTED_KEYS_0_NAME",
                                "Example Publisher",
                                "RAHDAR_PLUGINS_TRUSTED_KEYS_0_PEM",
                                PEM,
                                "RAHDAR_PLUGINS_TRUSTED_KEYS_1_NAME",
                                "Second",
                                "RAHDAR_PLUGINS_TRUSTED_KEYS_1_PEM",
                                PEM)));

        assertThat(bind(environment).trustedKeys())
                .containsExactly(
                        new PluginProperties.TrustedKey("Example Publisher", PEM),
                        new PluginProperties.TrustedKey("Second", PEM));
    }

    @Test
    void noKeysIsAnEmptyList() {
        assertThat(new PluginProperties(null, false, null, null, null, null, null).trustedKeys())
                .isEmpty();
    }
}
