package io.github.sudoitir.rahdar.feature.alerting;

import io.github.sudoitir.rahdar.kernel.auditfeed.AuditDeliveryHealth;
import io.github.sudoitir.rahdar.kernel.auditfeed.AuditDeliveryHealth.Receiver;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code AUDIT_DELIVERY} (ADR-0190): an audit receiver that lost records to retention, or whose
 * oldest unacknowledged record is older than {@code audit.delivery.alert-after}. The subject is
 * the receiver's plugin id; the value is the age of that record in seconds.
 */
@Component
@RequiredArgsConstructor
class AuditDeliverySignal implements InstallationSignalSource {

    private final AuditDeliveryHealth health;

    @Override
    public String condition() {
        return "AUDIT_DELIVERY";
    }

    @Override
    public AlertCondition.Evaluation evaluate() {
        Set<String> universe = new HashSet<>();
        Map<String, Double> active = new HashMap<>();
        for (Receiver receiver : health.receivers()) {
            universe.add(receiver.pluginId());
            if (receiver.behind()) {
                active.put(receiver.pluginId(), receiver.behindSeconds());
            }
        }
        return new AlertCondition.Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }
}
