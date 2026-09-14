package io.github.sudoitir.artemisstudio.kernel.plugin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.annotation.AliasFor;

/**
 * Marks a feature module's one configuration (ADR-0069, ADR-0070). The module's package is
 * scanned only while {@code artemis-studio.features.<id>.enabled} is not {@code false}, so a
 * disabled feature contributes no beans, endpoints, jobs or tools. The application itself
 * scans only the kernel and platform; {@code StudioFeatures} imports each feature.
 *
 * <p>The exclude filters are the ones {@code @SpringBootApplication} applies, so a test's
 * own configuration in the same package is not picked up.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Configuration(proxyBeanMethods = false)
@ConditionalOnFeature("")
@ComponentScan(
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
        })
public @interface FeatureModule {

    /** The feature id, as its descriptor declares it. */
    @AliasFor(annotation = ConditionalOnFeature.class, attribute = "value")
    String value();
}
