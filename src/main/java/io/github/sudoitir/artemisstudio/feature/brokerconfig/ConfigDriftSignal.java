package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import io.github.sudoitir.artemisstudio.feature.alerting.AlertCondition.Evaluation;
import io.github.sudoitir.artemisstudio.feature.alerting.AlertSignalSource;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateEntity;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Configuration drift for the built-in {@code CONFIG_DRIFT} rule (ADR-0067 D8). Read from the
 * recorded per-node state, never from a broker: the alert is a view of the last evaluation, at the
 * interval the operator set. Only evaluated nodes are in the universe; one that was unreachable or
 * not live cannot resolve a firing on the strength of no evidence.
 */
@Component
@RequiredArgsConstructor
class ConfigDriftSignal implements AlertSignalSource {

    private final BrokerConfigNodeStateRepository states;

    @Override
    public String condition() {
        return "CONFIG_DRIFT";
    }

    @Override
    public Evaluation evaluate(UUID clusterId) {
        Set<String> universe = new HashSet<>();
        Map<String, Double> active = new HashMap<>();
        for (BrokerConfigNodeStateEntity state : states.findByClusterId(clusterId)) {
            BrokerConfigNodeStateEntity.State s = state.state();
            if (s != BrokerConfigNodeStateEntity.State.IN_SYNC && s != BrokerConfigNodeStateEntity.State.DRIFTED) {
                continue;
            }
            String key = "node:" + state.getNodeId();
            universe.add(key);
            if (s == BrokerConfigNodeStateEntity.State.DRIFTED) {
                active.put(key, 1.0);
            }
        }
        return new Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }
}
