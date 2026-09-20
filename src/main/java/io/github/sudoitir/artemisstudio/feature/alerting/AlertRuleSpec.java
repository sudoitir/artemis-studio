package io.github.sudoitir.artemisstudio.feature.alerting;

import java.util.UUID;

/**
 * What an {@link AlertCondition} needs to evaluate a rule, in exported types only.
 *
 * <p>Conditions used to be handed the JPA entity directly. That made
 * {@link AlertCondition} un-implementable from any other module, because the entity lives
 * in {@code feature.alerting.internal.persistence} and Spring Modulith does not export it
 * — so a rule kind could only ever be added by editing this module. ADR-0089 needed a
 * condition evaluated from a consumer-health verdict, which {@code feature.triage} owns,
 * and {@code triage} already depends on {@code alerting}, so the reverse edge would be a
 * cycle.
 *
 * <p>Passing this projection instead makes the extension point real: any module already
 * permitted to depend on {@code alerting} can contribute a rule kind, and none of them can
 * reach the persistence model to do it.
 *
 * @param threshold the comparison value for a threshold rule; null for a state rule
 * @param scope the rule's raw scope JSON, parsed by {@code AlertScope}
 */
public record AlertRuleSpec(
        UUID id,
        boolean thresholdRule,
        String metric,
        String comparator,
        Double threshold,
        String scope,
        String stateCondition) {}
