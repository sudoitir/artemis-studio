package io.github.sudoitir.artemisstudio.app;

import io.github.sudoitir.artemisstudio.kernel.plugin.api.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.api.InstalledFeatures;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The composition root (ADR-0069): the one list of modules built into Studio.
 * Adding a module is one descriptor here and, for an optional feature, one
 * {@code @Import} of its {@code <Id>Feature} configuration.
 */
@Configuration(proxyBeanMethods = false)
public class StudioFeatures {

    /** Every installed module's descriptor, enabled or not. */
    public static List<FeatureDescriptor> descriptors() {
        return List.of();
    }

    @Bean
    InstalledFeatures installedFeatures() {
        return new InstalledFeatures(descriptors());
    }
}
