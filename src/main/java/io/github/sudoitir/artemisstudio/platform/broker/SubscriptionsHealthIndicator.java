package io.github.sudoitir.artemisstudio.platform.broker;

import io.github.sudoitir.artemisstudio.kernel.core.StudioHealth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * The {@code subscriptions} contributor: for every serving node Studio subscribes to, whether
 * its broker-notification subscription is established, and why when it is not. Degraded
 * while any is not established.
 */
@Component
@RequiredArgsConstructor
class SubscriptionsHealthIndicator extends AbstractHealthIndicator {

    private final CoreSubscriptionManager subscriptions;
    private final NodeDirectory nodes;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Map<UUID, String> names = nodes.nodes().stream()
                .collect(Collectors.toMap(NodeDirectory.KnownNode::id, NodeDirectory.KnownNode::name, (a, b) -> a));
        Map<String, Object> perNode = new LinkedHashMap<>();
        boolean lost = false;
        for (Map.Entry<UUID, CoreEventClient.State> e :
                subscriptions.nodeStates().entrySet()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("name", names.getOrDefault(e.getKey(), e.getKey().toString()));
            switch (e.getValue()) {
                case CoreEventClient.State.Connected c -> {
                    detail.put("established", true);
                    detail.put("since", c.since());
                }
                case CoreEventClient.State.Failed f -> {
                    lost = true;
                    detail.put("established", false);
                    detail.put("reason", f.kind() + ": " + f.reason());
                    detail.put("at", f.at());
                }
            }
            perNode.put(e.getKey().toString(), detail);
        }
        builder.status(lost ? StudioHealth.DEGRADED : Status.UP).withDetail("nodes", perNode);
    }
}
