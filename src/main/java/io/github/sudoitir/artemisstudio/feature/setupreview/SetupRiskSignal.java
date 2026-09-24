package io.github.sudoitir.artemisstudio.feature.setupreview;

import io.github.sudoitir.artemisstudio.feature.alerting.AlertCondition.Evaluation;
import io.github.sudoitir.artemisstudio.feature.alerting.AlertSignalSource;
import io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence.FindingKey;
import io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence.SetupFindingAcceptanceEntity;
import io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence.SetupFindingAcceptanceRepository;
import io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence.SetupFindingEntity;
import io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence.SetupFindingRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The {@code SETUP_RISK} state condition (ADR-0106), read from the persisted findings, never from a
 * broker — like {@code CONFIG_DRIFT}.
 *
 * <ul>
 *   <li>Every current warning or critical finding is in the universe, one subject each
 *       ({@code setup:<code>:<subject>}), so each tracks and resolves on its own.
 *   <li>A stale finding — its node did not answer the last review — stays in the universe and
 *       stays active: an unreachable node cannot resolve a risk.
 *   <li>An accepted finding is in the universe and inactive, so accepting a firing resolves it; an
 *       expired acceptance is not an acceptance.
 *   <li>A finding the review no longer produces is deleted, leaves the universe, and resolves
 *       through the evaluator's vanished-subject path.
 * </ul>
 */
@Component
@RequiredArgsConstructor
class SetupRiskSignal implements AlertSignalSource {

    private final SetupFindingRepository findings;
    private final SetupFindingAcceptanceRepository acceptances;

    @Override
    public String condition() {
        return "SETUP_RISK";
    }

    @Override
    public Evaluation evaluate(UUID clusterId) {
        Instant now = Instant.now();
        Map<FindingKey, SetupFindingAcceptanceEntity> accepted = new HashMap<>();
        for (SetupFindingAcceptanceEntity a : acceptances.findByClusterId(clusterId)) {
            accepted.put(new FindingKey(clusterId, a.getCode(), a.getSubject()), a);
        }
        Set<String> universe = new HashSet<>();
        Map<String, Double> active = new HashMap<>();
        for (SetupFindingEntity f : findings.findByClusterId(clusterId)) {
            if (Severity.INFO.name().equals(f.getSeverity())) {
                continue;
            }
            String key = "setup:" + f.getCode() + ":" + f.getSubject();
            universe.add(key);
            SetupFindingAcceptanceEntity a = accepted.get(new FindingKey(clusterId, f.getCode(), f.getSubject()));
            if (a == null || !a.activeAt(now)) {
                active.put(key, Severity.CRITICAL.name().equals(f.getSeverity()) ? 2.0 : 1.0);
            }
        }
        return new Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }
}
