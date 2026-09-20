package io.github.sudoitir.artemisstudio.feature.alerting;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One rule kind's predicate (ADR-0035): the full set of subjects the rule
 * considers this tick, and the subset currently meeting its condition, with the
 * value to report for each. {@link Evaluation#universe} is what lets the evaluator resolve
 * a subject that has vanished (e.g. a deleted queue) rather than leave it stuck
 * PENDING/FIRING forever — a subject present in {@code universe} but absent from
 * {@code active} has a condition that is simply false; a subject absent from
 * {@code universe} entirely no longer exists.
 *
 * <p>Implementations are discovered as beans and asked in {@code @Order} sequence whether
 * they {@link #supports(AlertRuleSpec)} a rule; the first that does evaluates it. Derived
 * metrics therefore order ahead of the raw gauge and rate conditions, which match on
 * broader sets. An implementation may live in any module allowed to depend on
 * {@code feature.alerting} (ADR-0089).
 */
public interface AlertCondition {

    record Evaluation(Set<String> universe, Map<String, Double> active) {
        public static final Evaluation EMPTY = new Evaluation(Set.of(), Map.of());
    }

    /**
     * Whether this condition is the one that evaluates the given rule. Must be exact:
     * two conditions claiming one rule means the {@code @Order} decides, silently.
     */
    boolean supports(AlertRuleSpec rule);

    Evaluation evaluate(UUID clusterId, AlertRuleSpec rule);
}
