package io.github.sudoitir.artemisstudio.kernel.replica;

import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * The {@code replica} contributor to readiness (ADR-0148): up once this replica is ready, which is
 * after the plugin boot sequence, and the bus listens or has been down for less than ten seconds;
 * down while it is starting, while it drains and once the bus has been gone for longer. It says
 * nothing about brokers, as the operational health rule requires.
 */
@Component
@RequiredArgsConstructor
class ReplicaHealthIndicator extends AbstractHealthIndicator {

    static final Duration BUS_GRACE = Duration.ofSeconds(10);

    private final ReplicaRegistry registry;
    private final StudioBus bus;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        ReplicaRegistry.State state = registry.state();
        builder.withDetail("replica", registry.id().toString()).withDetail("state", state.name());
        Instant busDownSince = bus.downSince().orElse(null);
        if (state != ReplicaRegistry.State.READY) {
            builder.down();
        } else if (busDownSince != null
                && Duration.between(busDownSince, Instant.now()).compareTo(BUS_GRACE) > 0) {
            builder.down().withDetail("busDownSince", busDownSince.toString());
        } else {
            builder.up();
        }
    }
}
