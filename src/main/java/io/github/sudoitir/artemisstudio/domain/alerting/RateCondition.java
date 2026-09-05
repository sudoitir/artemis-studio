package io.github.sudoitir.artemisstudio.domain.alerting;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.persist.AlertRuleEntity;
import io.github.sudoitir.artemisstudio.persist.MetricSeriesRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the current per-queue rate from {@code metric_sample} over a
 * 2×tier-B window (design.md decision 2) — one query per {@code (cluster, metric)}
 * per tick regardless of rule count. Uses {@link MetricSeriesRepository}'s
 * restart-safe, never-negative rate derivation; a subject with fewer than two
 * samples in the window is simply absent, not zero.
 */
@Component
@RequiredArgsConstructor
public class RateCondition implements AlertCondition {

    private static final Set<String> RATE_METRICS = Set.of("messagesAdded", "messagesAcked");

    private final MetricSeriesRepository series;
    private final ArtemisStudioProperties properties;
    private final ObjectMapper mapper;

    public static boolean supports(String metric) {
        return RATE_METRICS.contains(metric);
    }

    @Override
    public Evaluation evaluate(UUID clusterId, AlertRuleEntity rule) {
        if (!RATE_METRICS.contains(rule.getMetric())) {
            return Evaluation.EMPTY;
        }
        AlertScope scope = AlertScope.parse(rule.getScope(), mapper);
        Instant to = Instant.now();
        Instant from = to.minus(properties.scrape().tierBInterval().multipliedBy(2));

        Map<String, Double> ratesByQueue = series.latestRateBySubject(clusterId, rule.getMetric(), from, to);
        Set<String> universe = new java.util.HashSet<>();
        Map<String, Double> active = new HashMap<>();
        ratesByQueue.forEach((queueName, rate) -> {
            if (!scope.matchesQueue(queueName)) {
                return;
            }
            String key = "queue:" + queueName;
            universe.add(key);
            if (Comparators.test(rule.getComparator(), rate, rule.getThreshold())) {
                active.put(key, rate);
            }
        });
        return new Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }
}
