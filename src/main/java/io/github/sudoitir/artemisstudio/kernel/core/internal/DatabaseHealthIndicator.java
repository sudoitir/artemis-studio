package io.github.sudoitir.artemisstudio.kernel.core.internal;

import io.github.sudoitir.artemisstudio.kernel.core.StudioHealth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/** The {@code database} contributor: degraded while the boot preflight has a warning about the server. */
@Component
@RequiredArgsConstructor
class DatabaseHealthIndicator extends AbstractHealthIndicator {

    private final DatabasePreflight preflight;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        List<String> warnings = preflight.warnings();
        builder.status(warnings.isEmpty() ? Status.UP : StudioHealth.DEGRADED).withDetail("warnings", warnings);
    }
}
