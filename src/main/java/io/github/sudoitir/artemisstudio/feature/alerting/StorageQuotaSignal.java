package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.LifecycleService;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.LifecycleService.StoreState;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code STORAGE_QUOTA} (ADR-0135): a store whose usage has passed its quota's warning share. A
 * store with no quota is never active. The value is the share of the quota used, in percent.
 */
@Component
@RequiredArgsConstructor
class StorageQuotaSignal implements InstallationSignalSource {

    private final LifecycleService lifecycle;

    @Override
    public String condition() {
        return "STORAGE_QUOTA";
    }

    @Override
    public AlertCondition.Evaluation evaluate() {
        Set<String> universe = new HashSet<>();
        Map<String, Double> active = new HashMap<>();
        for (StoreState state : lifecycle.states()) {
            String id = state.store().id();
            universe.add(id);
            if (state.overWarning()) {
                active.put(id, state.quotaUsedPercent().doubleValue());
            }
        }
        return new AlertCondition.Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }
}
