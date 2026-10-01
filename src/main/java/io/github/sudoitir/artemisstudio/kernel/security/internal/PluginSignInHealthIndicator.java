package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.core.StudioHealth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * The {@code pluginSignIn} contributor (ADR-0156): degraded while a plugin's sign-in provider's latest call
 * failed, naming the provider and the reason; the next call that succeeds clears it. Local sign-in
 * never waits on a plugin, so this says a directory is unreachable, not that Studio is.
 */
@Component
@RequiredArgsConstructor
public class PluginSignInHealthIndicator extends AbstractHealthIndicator {

    private final PluginSignIn signIn;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        List<String> failing = signIn.failures().stream()
                .sorted(java.util.Comparator.comparing(PluginSignIn.Failure::providerId))
                .map(f -> f.providerId() + ": " + f.reason())
                .toList();
        builder.status(failing.isEmpty() ? Status.UP : StudioHealth.DEGRADED).withDetail("failing", failing);
    }
}
