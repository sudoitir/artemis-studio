package io.github.sudoitir.artemisstudio.kernel.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.plugin.fixture.demo.DemoComponent;
import io.github.sudoitir.artemisstudio.kernel.plugin.fixture.demo.DemoFeature;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * A {@link FeatureModule} scans its package while the feature is enabled and contributes
 * nothing at all while it is disabled (ADR-0069).
 */
class FeatureModuleTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(DemoFeature.class);

    @Test
    void anEnabledFeatureScansItsPackage() {
        runner.run(context -> assertThat(context).hasSingleBean(DemoComponent.class));
    }

    @Test
    void anExplicitlyEnabledFeatureScansItsPackage() {
        runner.withPropertyValues("artemis-studio.features.demo.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DemoComponent.class));
    }

    @Test
    void aDisabledFeatureContributesNoBeans() {
        runner.withPropertyValues("artemis-studio.features.demo.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(DemoFeature.class);
            assertThat(context).doesNotHaveBean(DemoComponent.class);
        });
    }
}
