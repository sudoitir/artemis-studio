package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.platform.scrape.MetricSamples;
import io.github.sudoitir.artemisstudio.platform.scrape.ScrapeProperties;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the current per-queue rate from {@code metric_sample} over a
 * 2×tier-B window (design.md decision 2) — one query per {@code (cluster, metric)}
 * per tick regardless of rule count. Uses {@link MetricSamples}'s
 * restart-safe, never-negative rate derivation; a subject with fewer than two
 * samples in the window is simply absent, not zero.
 */
@Component
@Order(40)
@RequiredArgsConstructor
public class RateCondition implements AlertCondition {

    private static final Set<String> RATE_METRICS = Set.of("messagesAdded", "messagesAcked");

    private final MetricSamples series;
    private final ScrapeProperties properties;
    private final ObjectMapper mapper;

    @Override
    public boolean supports(AlertRuleSpec rule) {
        return rule.thresholdRule() && RATE_METRICS.contains(rule.metric());
    }

    @Override
    public Evaluation evaluate(UUID clusterId, AlertRuleSpec rule) {
        if (!supports(rule)) {
            return Evaluation.EMPTY;
        }
        AlertScope scope = AlertScope.parse(rule.scope(), mapper);
        Instant to = Instant.now();
        Instant from = to.minus(properties.tierBInterval().multipliedBy(2));

        Map<String, Double> ratesByQueue = series.latestRateBySubject(clusterId, rule.metric(), from, to);
        Set<String> universe = new java.util.HashSet<>();
        Map<String, Double> active = new HashMap<>();
        ratesByQueue.forEach((queueName, rate) -> {
            if (!scope.matchesQueue(queueName)) {
                return;
            }
            String key = "queue:" + queueName;
            universe.add(key);
            if (Comparators.test(rule.comparator(), rate, rule.threshold())) {
                active.put(key, rate);
            }
        });
        return new Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }
}
