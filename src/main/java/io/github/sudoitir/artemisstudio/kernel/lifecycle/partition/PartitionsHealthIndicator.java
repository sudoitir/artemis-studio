package io.github.sudoitir.artemisstudio.kernel.lifecycle.partition;

import java.util.List;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/** The {@code partitions} contributor: DOWN while a partitioned table has no partition for the current period. */
@Component
class PartitionsHealthIndicator extends AbstractHealthIndicator {

    private final PartitionMaintainer maintainer;

    PartitionsHealthIndicator(PartitionMaintainer maintainer) {
        this.maintainer = maintainer;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        List<String> missing = maintainer.missingCurrent();
        builder.status(
                        missing.isEmpty()
                                ? org.springframework.boot.health.contributor.Status.UP
                                : org.springframework.boot.health.contributor.Status.DOWN)
                .withDetail("missing", missing);
    }
}
