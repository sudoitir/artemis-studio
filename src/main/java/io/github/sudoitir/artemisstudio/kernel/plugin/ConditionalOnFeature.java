package io.github.sudoitir.artemisstudio.kernel.plugin;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.OnFeatureCondition;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Loads the annotated feature configuration only while
 * {@code artemis-studio.features.<id>.enabled} is not {@code false} (ADR-0069).
 * Put it on the one {@code <Id>Feature} configuration of an optional module.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnFeatureCondition.class)
public @interface ConditionalOnFeature {

    /** The feature id. */
    String value();
}
