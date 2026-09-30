package io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust;

import io.github.sudoitir.artemisstudio.kernel.core.StudioHealth;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * The {@code pluginTrust} contributor (design.md §8): degraded while any installed plugin is not
 * signed by a trusted key, naming those plugins. Computed from the current keys, so removing a key
 * degrades it at once.
 */
@Component
@RequiredArgsConstructor
public class PluginTrustHealthIndicator extends AbstractHealthIndicator {

    private final PluginInstallRepository installs;
    private final PluginTrust trust;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        List<String> unverified = installs.findAll().stream()
                .filter(e -> e.status() != PluginInstallStatus.UNINSTALLED)
                .filter(e -> trust.decide(e.getSignerFingerprint(), e.getSignerSubject())
                                .status()
                        != TrustDecision.Status.TRUSTED)
                .map(PluginInstallEntity::getId)
                .sorted()
                .toList();
        builder.status(unverified.isEmpty() ? Status.UP : StudioHealth.DEGRADED).withDetail("unverified", unverified);
    }
}
