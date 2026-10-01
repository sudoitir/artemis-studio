package io.github.sudoitir.artemisstudio.kernel.plugin.internal.license;

import io.github.sudoitir.artemisstudio.kernel.core.StudioHealth;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginLicenseStore;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginLicenseStore.Summary;
import java.time.Clock;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * The {@code pluginLicenses} contributor (ADR-0153): degraded while a running plugin that needs a
 * license has none, has one its plugin has not accepted, or has one that expires within 30 days,
 * naming each plugin and its state. A file whose plugin has not reported yet gets
 * {@link PluginLicenseStore#UNCHECKED_GRACE} first. Computed from the shared tables, so every replica
 * agrees, and it is not part of readiness: an unlicensed plugin never takes Studio out of rotation.
 */
@Component
@RequiredArgsConstructor
public class PluginLicensesHealthIndicator extends AbstractHealthIndicator {

    private final PluginLicenseStore licenses;
    private final Clock clock;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Map<String, String> needing = new TreeMap<>();
        licenses.activeDeclaring().forEach((id, summary) -> {
            if (needsAttention(summary)) {
                needing.put(id, summary.state().name());
            }
        });
        builder.status(needing.isEmpty() ? Status.UP : StudioHealth.DEGRADED).withDetail("plugins", needing);
    }

    private boolean needsAttention(Summary summary) {
        return switch (summary.state()) {
            case VALID -> false;
            case UNCHECKED ->
                summary.uploadedAt().plus(PluginLicenseStore.UNCHECKED_GRACE).isBefore(clock.instant());
            case MISSING, EXPIRING, EXPIRED, OVER_LIMIT, INVALID -> true;
        };
    }
}
