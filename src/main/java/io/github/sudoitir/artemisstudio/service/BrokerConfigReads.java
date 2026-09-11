package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.brokerconfig.BrokerConfigOperations;
import io.github.sudoitir.artemisstudio.broker.brokerconfig.BrokerConfigOperations.ReadScope;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.ObservedNodeConfig;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The observed side of every configuration comparison: one logical node per
 * cluster (the live member of an HA pair, from polled topology — never from
 * configuration, non-negotiable #4), each read through the per-node limiter, and a
 * read that fails turned into an {@code unreachable} observation rather than an
 * exception, so one dead node never hides the others.
 */
@Component
@RequiredArgsConstructor
public class BrokerConfigReads {

    private final BrokerNodeRepository brokerNodes;
    private final BrokerConnections connections;
    private final BrokerConfigOperations ops;
    private final NodeCallLimiter limiter;

    /** The cluster's logical nodes, live member first where one exists. */
    public List<BrokerNodeEntity> targets(UUID clusterId) {
        return ServingNodes.from(brokerNodes.findByClusterIdOrderByNameAsc(clusterId));
    }

    /** Observe every logical node of the cluster within {@code scope}. */
    public List<ObservedNodeConfig> observe(UUID clusterId, ReadScope scope) {
        List<ObservedNodeConfig> out = new ArrayList<>();
        for (BrokerNodeEntity node : targets(clusterId)) {
            out.add(observe(clusterId, node, scope));
        }
        return out;
    }

    /** Observe one node; not-live and unreachable are answers, not failures. */
    public ObservedNodeConfig observe(UUID clusterId, BrokerNodeEntity node, ReadScope scope) {
        if (!Boolean.TRUE.equals(node.getActive())) {
            return ObservedNodeConfig.notLive(node.getId(), node.getName());
        }
        try {
            return ops.read(client(clusterId, node), node.getId(), node.getName(), scope);
        } catch (BrokerConnectionException e) {
            return ObservedNodeConfig.unreachable(node.getId(), node.getName(), e.kind() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            return ObservedNodeConfig.unreachable(node.getId(), node.getName(), "BAD_RESPONSE: " + e.getMessage());
        }
    }

    /** A client for one node, after a limiter permit; a write path uses this too. */
    public JolokiaBrokerClient client(UUID clusterId, BrokerNodeEntity node) {
        try {
            limiter.acquire(node.getId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE, "Timed out waiting for a per-node call permit.");
        }
        return connections.forCluster(clusterId, node.getJolokiaUrl());
    }
}
