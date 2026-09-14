package io.github.sudoitir.artemisstudio.platform.broker;

import io.github.sudoitir.artemisstudio.kernel.core.StudioHealth;
import io.github.sudoitir.artemisstudio.platform.broker.NodeCallHealth.Calls;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * The {@code brokers} contributor: for every registered node, when a management call last
 * succeeded and failed and how long calls last waited on the per-node rate limit; for every
 * cluster, how many Core connections are open. Degraded while any node's latest call failed.
 * Built entirely from what earlier calls recorded — a health read never calls a broker.
 */
@Component
@RequiredArgsConstructor
class BrokersHealthIndicator extends AbstractHealthIndicator {

    private final NodeDirectory nodes;
    private final NodeCallHealth calls;
    private final NodeCallLimiter limiter;
    private final CorePool corePool;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Map<String, Object> perNode = new LinkedHashMap<>();
        Set<UUID> clusters = new LinkedHashSet<>();
        boolean failing = false;
        for (NodeDirectory.KnownNode node : nodes.nodes()) {
            clusters.add(node.clusterId());
            Optional<Calls> latest = node.jolokiaUrl() == null ? Optional.empty() : calls.calls(node.jolokiaUrl());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("name", node.name());
            detail.put("clusterId", node.clusterId());
            detail.put("lastSuccess", latest.map(Calls::lastSuccess).orElse(null));
            detail.put("lastFailure", latest.map(Calls::lastFailure).orElse(null));
            detail.put("lastError", latest.map(Calls::lastError).orElse(null));
            detail.put("rateLimitWaitMs", limiter.lastWait(node.id()).toMillis());
            failing |= latest.map(Calls::failing).orElse(false);
            perNode.put(node.id().toString(), detail);
        }
        Map<String, Integer> coreConnections = new LinkedHashMap<>();
        clusters.forEach(id -> coreConnections.put(id.toString(), corePool.connections(id)));
        builder.status(failing ? StudioHealth.DEGRADED : Status.UP)
                .withDetail("nodes", perNode)
                .withDetail("coreConnections", coreConnections);
    }
}
