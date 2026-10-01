package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerIdentityClaims;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Keeps a registered cluster's claims in {@code broker_identity} equal to what its nodes carry now
 * (ADR-0167). Called in the same transaction as every write that can change that: discovery adding a node
 * or learning its NodeID, a NodeID read that differs from the stored one (a broker whose journal was
 * wiped), and an operator's management URL override. A claim that outlived its node would otherwise refuse
 * an unrelated broker that later takes the old URL or NodeID, and a node found after registration would
 * have no claim to settle a race with.
 */
@Component
@RequiredArgsConstructor
class ClusterIdentityClaims {

    private final BrokerNodeRepository nodes;
    private final BrokerIdentityClaims claims;

    /** Rewrites the cluster's claims to its nodes' current identities. */
    void sync(UUID clusterId) {
        claims.replace(clusterId, ClusterIdentity.claimsOf(nodes.findByClusterIdOrderByNameAsc(clusterId)));
    }
}
