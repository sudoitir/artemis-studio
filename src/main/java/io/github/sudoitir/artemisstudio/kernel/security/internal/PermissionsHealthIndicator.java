package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.core.StudioHealth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * The {@code permissions} contributor: degraded when a guard or plugin manifest names a permission
 * the catalogue does not hold, or a catalogue entry is undescribed or misdeclared.
 */
@Component
@RequiredArgsConstructor
class PermissionsHealthIndicator extends AbstractHealthIndicator {

    private final PermissionDeclarations declarations;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        List<String> mismatches = declarations.mismatches();
        builder.status(mismatches.isEmpty() ? Status.UP : StudioHealth.DEGRADED).withDetail("mismatches", mismatches);
    }
}
