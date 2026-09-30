package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.StorageHealthService;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StorageHealthService.TableHealth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code STORAGE_HEALTH} (ADR-0135): a table with a problem, such as bloat or a missing partition.
 * The subject is {@code schema.table}; the value is its dead-tuple share in percent, or 1 when the
 * problem is not bloat.
 */
@Component
@RequiredArgsConstructor
class StorageHealthSignal implements InstallationSignalSource {

    private final StorageHealthService health;

    @Override
    public String condition() {
        return "STORAGE_HEALTH";
    }

    @Override
    public AlertCondition.Evaluation evaluate() {
        Set<String> universe = new HashSet<>();
        Map<String, Double> active = new HashMap<>();
        for (TableHealth table : health.health()) {
            String subject = table.schema() + "." + table.name();
            universe.add(subject);
            if (!table.healthy()) {
                active.put(subject, table.deadPercent() > 0 ? (double) table.deadPercent() : 1.0);
            }
        }
        return new AlertCondition.Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }
}
