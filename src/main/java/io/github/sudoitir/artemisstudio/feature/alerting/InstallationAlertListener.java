package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.StorageSampled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Re-evaluates the installation's state rules on fresh storage numbers (ADR-0133). */
@Component
@RequiredArgsConstructor
@Slf4j
class InstallationAlertListener {

    private final AlertEvaluator evaluator;

    @EventListener
    void onStorageSampled(StorageSampled event) {
        try {
            evaluator.evaluateInstallation("STATE");
        } catch (RuntimeException e) {
            log.warn("Installation alert evaluation failed: {}", e.toString());
        }
    }
}
