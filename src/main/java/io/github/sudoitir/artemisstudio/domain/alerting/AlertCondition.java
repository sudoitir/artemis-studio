package io.github.sudoitir.artemisstudio.domain.alerting;

import io.github.sudoitir.artemisstudio.persist.AlertRuleEntity;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One rule kind's predicate (ADR-0035): the full set of subjects the rule
 * considers this tick, and the subset currently meeting its condition, with the
 * value to report for each. {@link #universe} is what lets the evaluator resolve
 * a subject that has vanished (e.g. a deleted queue) rather than leave it stuck
 * PENDING/FIRING forever — a subject present in {@code universe} but absent from
 * {@code active} has a condition that is simply false; a subject absent from
 * {@code universe} entirely no longer exists.
 */
public interface AlertCondition {

    record Evaluation(Set<String> universe, Map<String, Double> active) {
        public static final Evaluation EMPTY = new Evaluation(Set.of(), Map.of());
    }

    Evaluation evaluate(UUID clusterId, AlertRuleEntity rule);
}
