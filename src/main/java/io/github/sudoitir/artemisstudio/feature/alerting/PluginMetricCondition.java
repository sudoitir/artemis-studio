package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.platform.scrape.PluginMetrics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Threshold rules on a plugin's metrics (ADR-0113), over the values {@link PluginMetrics} sampled
 * on the same tier-B tick. Subjects are keyed {@code <subject kind>:<subject>}. A saved rule
 * whose plugin is not running evaluates to nothing, so it neither fires nor stays firing, and is
 * listed as having no source; a new rule must name a metric of a running plugin.
 */
@Component
@Order(5)
@RequiredArgsConstructor
public class PluginMetricCondition implements AlertCondition {

    private final PluginMetrics metrics;
    private final ObjectMapper mapper;

    @Override
    public boolean supports(AlertRuleSpec rule) {
        if (!rule.thresholdRule() || rule.metric() == null || !rule.metric().contains(":")) {
            return false;
        }
        // Plugin metrics are namespaced (<plugin id>:<name>); Studio's own have no colon. A saved rule keeps
        // its condition while its plugin is away; a new one (no id yet) needs a running plugin to declare it.
        return rule.id() != null || metrics.declared(rule.metric()).isPresent();
    }

    /** Whether the metric belongs to a running plugin, or is not a plugin metric at all. */
    public boolean available(String metric) {
        return metric == null
                || !metric.contains(":")
                || metrics.declared(metric).isPresent();
    }

    /** The metrics of running plugins. */
    public List<PluginMetrics.Declared> declared() {
        return metrics.declared();
    }

    @Override
    public Evaluation evaluate(UUID clusterId, AlertRuleSpec rule) {
        var declared = metrics.declared(rule.metric());
        if (declared.isEmpty()) {
            return Evaluation.EMPTY;
        }
        String kind = declared.get().metric().subject();
        AlertScope scope = AlertScope.parse(rule.scope(), mapper);
        Map<String, Double> active = new HashMap<>();
        List<String> universe = new java.util.ArrayList<>();
        metrics.latest(clusterId, rule.metric()).forEach((subject, value) -> {
            if (!scope.matchesSubject(subject)) {
                return;
            }
            String key = kind + ":" + subject;
            universe.add(key);
            if (Comparators.test(rule.comparator(), value, rule.threshold())) {
                active.put(key, value);
            }
        });
        return new Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }
}
