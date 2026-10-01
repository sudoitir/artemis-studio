package io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code broker_identity}: which cluster each broker belongs to (ADR-0167). Its primary key is the
 * database's half of "a broker is registered once"; the registration check over {@code broker_node}
 * is the other, and the one that names the cluster.
 */
@Component
@RequiredArgsConstructor
public class BrokerIdentityClaims {

    private final JdbcClient jdbc;

    /**
     * Claims the identities for a cluster in the caller's transaction, and returns another cluster that
     * already holds one of them, if any. One statement, in key order, so two registrations claiming the
     * same brokers lock them in the same order. A claim another transaction has written but not committed
     * makes this wait for it: committed, it is that cluster's and is returned here; rolled back, the claim
     * is this cluster's.
     *
     * @param claims the identities by kind ({@code NODE_ID} or {@code URL})
     */
    public Optional<UUID> claim(UUID clusterId, Map<String, Set<String>> claims) {
        List<String> kinds = new ArrayList<>();
        List<String> identities = new ArrayList<>();
        claims.forEach((kind, values) -> values.forEach(value -> {
            kinds.add(kind);
            identities.add(value);
        }));
        if (kinds.isEmpty()) {
            return Optional.empty();
        }
        Object[] kindArray = kinds.toArray(String[]::new);
        Object[] identityArray = identities.toArray(String[]::new);
        jdbc.sql("""
                        INSERT INTO broker_identity (kind, identity, cluster_id)
                        SELECT t.kind, t.identity, ? FROM unnest(?::text[], ?::text[]) AS t (kind, identity)
                        ORDER BY t.kind, t.identity
                        ON CONFLICT DO NOTHING
                        """).params(clusterId, kindArray, identityArray).update();
        return jdbc.sql("""
                        SELECT b.cluster_id FROM broker_identity b
                        JOIN unnest(?::text[], ?::text[]) AS t (kind, identity)
                          ON b.kind = t.kind AND b.identity = t.identity
                        WHERE b.cluster_id <> ?
                        LIMIT 1
                        """)
                .params(kindArray, identityArray, clusterId)
                .query(UUID.class)
                .optional();
    }
}
