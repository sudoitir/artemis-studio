package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaRegistry;
import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaRegistry.Seen;
import io.github.sudoitir.artemisstudio.platform.broker.ReplicaDirectory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The replicas and the clusters each one holds a lease on (ADR-0148), for the health view. */
@Component
@RequiredArgsConstructor
class ClusterReplicaDirectory implements ReplicaDirectory {

    /** A replica that crashed stays listed this long, so an operator sees it. */
    private static final Duration RECENT = Duration.ofMinutes(10);

    private final ReplicaRegistry registry;
    private final JdbcTemplate jdbc;

    @Override
    public UUID self() {
        return registry.id();
    }

    @Override
    public List<KnownReplica> replicas() {
        Map<UUID, List<OwnedCluster>> owned = new HashMap<>();
        jdbc.query("""
                SELECT l.replica_id, c.id, c.name FROM cluster_lease l JOIN cluster c ON c.id = l.cluster_id
                WHERE l.expires_at > now() ORDER BY c.name
                """, rs -> {
            owned.computeIfAbsent(rs.getObject("replica_id", UUID.class), _ -> new ArrayList<>())
                    .add(new OwnedCluster(rs.getObject("id", UUID.class), rs.getString("name")));
        });
        return registry.seen(RECENT).stream()
                .map(s -> known(s, owned.getOrDefault(s.replica().id(), List.of())))
                .toList();
    }

    private static KnownReplica known(Seen seen, List<OwnedCluster> clusters) {
        ReplicaRegistry.Replica r = seen.replica();
        return new KnownReplica(
                r.id(),
                r.host(),
                r.version(),
                r.state().name().toLowerCase(Locale.ROOT),
                r.startedAt(),
                seen.heartbeatAgeMillis(),
                seen.gone(),
                clusters);
    }
}
