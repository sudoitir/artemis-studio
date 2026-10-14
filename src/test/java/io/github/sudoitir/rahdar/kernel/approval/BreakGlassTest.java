package io.github.sudoitir.rahdar.kernel.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Break-glass is accepted from the process environment and system properties only (ADR-0184). */
class BreakGlassTest {

    private static StandardEnvironment environment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of()));
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                        new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of()));
        ConfigurationPropertySources.attach(environment);
        return environment;
    }

    @Test
    void unsetIsOff() {
        assertThat(BreakGlass.reasonFrom(environment())).isNull();
    }

    @Test
    void theProcessEnvironmentSetsIt() {
        StandardEnvironment environment = environment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("RAHDAR_GATE_BREAK_GLASS", " INC-42 provider down ")));

        assertThat(BreakGlass.reasonFrom(environment)).isEqualTo("INC-42 provider down");
    }

    @Test
    void aSystemPropertySetsIt() {
        StandardEnvironment environment = environment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                        new MapPropertySource(
                                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                                Map.of(BreakGlass.PROPERTY, "INC-7")));

        assertThat(BreakGlass.reasonFrom(environment)).isEqualTo("INC-7");
    }

    @Test
    void theDatabaseBackedConfigurationMayNotSetItAndStartupFails() {
        StandardEnvironment environment = environment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "bootstrapProperties-rahdarConfigProperty", Map.of(BreakGlass.PROPERTY, "from a row")));

        assertThatThrownBy(() -> BreakGlass.reasonFrom(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bootstrapProperties-rahdarConfigProperty");
    }

    @Test
    void aRelaxedSpellingInAConfigurationFileIsRefusedToo() {
        StandardEnvironment environment = environment();
        environment
                .getPropertySources()
                .addLast(new MapPropertySource(
                        "Config resource 'class path resource [application.yml]'",
                        Map.of("rahdar.gate.breakGlass", "sneaky")));

        assertThatThrownBy(() -> BreakGlass.reasonFrom(environment)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAllowedSourceDoesNotExcuseAnotherThatAlsoSetsIt() {
        StandardEnvironment environment = environment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                        new MapPropertySource(
                                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                                Map.of(BreakGlass.PROPERTY, "INC-7")));
        environment
                .getPropertySources()
                .addLast(new MapPropertySource(
                        "bootstrapProperties-rahdarConfigProperty", Map.of(BreakGlass.PROPERTY, "from a row")));

        assertThatThrownBy(() -> BreakGlass.reasonFrom(environment)).isInstanceOf(IllegalStateException.class);
    }
}
