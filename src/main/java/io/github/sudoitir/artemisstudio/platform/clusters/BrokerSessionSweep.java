package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.kernel.replica.BusResumed;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerSessions;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * After the bus was down, a {@code cluster-deleted} signal may have been missed, so the broker
 * sessions and pools this replica still holds for a cluster that is no longer in the database are
 * released (ADR-0152). Lives here because the cluster table is this module's.
 */
@Component
@RequiredArgsConstructor
class BrokerSessionSweep {

    private final ClusterRepository clusters;
    private final BrokerSessions sessions;

    @EventListener
    void on(BusResumed resumed) {
        sessions.releaseAllBut(
                clusters.findAll().stream().map(ClusterEntity::getId).collect(Collectors.toSet()));
    }
}
