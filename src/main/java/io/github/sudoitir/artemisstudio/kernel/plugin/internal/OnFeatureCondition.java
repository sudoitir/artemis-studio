package io.github.sudoitir.artemisstudio.kernel.plugin.internal;

import io.github.sudoitir.artemisstudio.kernel.plugin.ConditionalOnFeature;
import io.github.sudoitir.artemisstudio.kernel.plugin.Contract;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Evaluates {@link ConditionalOnFeature}; reports its decision in the conditions report. */
public class OnFeatureCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var attributes = metadata.getAnnotationAttributes(ConditionalOnFeature.class.getName());
        String id = (String) attributes.get("value");
        String property = Contract.enabledProperty(id);
        boolean enabled = context.getEnvironment().getProperty(property, Boolean.class, true);
        return enabled
                ? ConditionOutcome.match("feature '" + id + "' is enabled")
                : ConditionOutcome.noMatch("feature '" + id + "' is disabled by " + property + "=false");
    }
}
