package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.platform.scrape.ScrapeTierCompleted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Evaluates rules right after the tier their data source depends on has persisted (ADR-0035)
 * — never on an independent timer. Tier A persisted HA state, so state-condition rules
 * (split-brain, node down, replication behind, cluster degraded) evaluate; tiers B and C
 * persisted queue snapshots and metric samples, so metric-threshold rules do.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class AlertScrapeListener {

    private final AlertEvaluator evaluator;

    @EventListener
    void onTierCompleted(ScrapeTierCompleted event) {
        String kind = event.tier() == ScrapeTierCompleted.Tier.A ? "STATE" : "METRIC_THRESHOLD";
        try {
            evaluator.evaluate(event.clusterId(), kind);
        } catch (RuntimeException e) {
            log.warn("{} alert evaluation failed for cluster {}: {}", kind, event.clusterId(), e.toString());
        }
    }
}
