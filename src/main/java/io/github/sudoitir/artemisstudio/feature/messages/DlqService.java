package io.github.sudoitir.artemisstudio.feature.messages;

import io.github.sudoitir.artemisstudio.feature.messages.web.MessageViews.DlqAddress;
import io.github.sudoitir.artemisstudio.feature.messages.web.MessageViews.DlqQueue;
import io.github.sudoitir.artemisstudio.feature.messages.web.MessageViews.DlqQueueDepth;
import io.github.sudoitir.artemisstudio.feature.messages.web.MessageViews.DlqView;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshot;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshots;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Dead-letter / expiry view (ADR-0021, D8). The DLA and EA come from the broker's
 * own {@code getAddressSettingsAsJSON("#")}, never a name match — if that read
 * fails the view says so ({@code settingsAvailable = false}) and infers nothing.
 * Queues on those addresses and their per-node depth come from the
 * {@code queue_snapshot} cache.
 */
@Service
@RequiredArgsConstructor
public class DlqService {

    private final QueueSnapshots queueSnapshots;
    private final ClusterDirectory brokerNodes;
    private final BrokerConnections connections;
    private final NodeCallLimiter limiter;
    private final ClusterAccessGuard clusterAccess;

    @Transactional(readOnly = true)
    public DlqView view(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, MessagePermissions.MESSAGE_READ);
        ClusterNode manageable = brokerNodes.nodes(clusterId).stream()
                .filter(n -> n.getJolokiaUrl() != null)
                .findFirst()
                .orElseThrow(() -> new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNREACHABLE,
                        "This cluster has no node with a management URL yet."));

        Map<String, String> kinds = new LinkedHashMap<>();
        boolean settingsAvailable;
        try {
            acquire(manageable.getId());
            JolokiaBrokerClient client = connections.forCluster(clusterId, manageable.getJolokiaUrl());
            JsonNode settings = client.execOnBrokerParsed("getAddressSettingsAsJSON(java.lang.String)", "#");
            String dla = text(settings, "deadLetterAddress");
            String ea = text(settings, "expiryAddress");
            if (dla != null && !dla.isBlank()) {
                kinds.put(dla, "dead-letter");
            }
            if (ea != null && !ea.isBlank()) {
                kinds.putIfAbsent(ea, "expiry");
            }
            settingsAvailable = true;
        } catch (BrokerConnectionException e) {
            settingsAvailable = false;
        }

        if (!settingsAvailable) {
            return new DlqView(List.of(), false);
        }

        Map<UUID, String> nodeNames = new LinkedHashMap<>();
        brokerNodes.nodes(clusterId).forEach(n -> nodeNames.put(n.getId(), n.getName()));
        List<QueueSnapshot> all = queueSnapshots.forCluster(clusterId);

        List<DlqAddress> addresses = new ArrayList<>();
        for (Map.Entry<String, String> entry : kinds.entrySet()) {
            String address = entry.getKey();
            Map<String, List<QueueSnapshot>> byQueue = new LinkedHashMap<>();
            for (QueueSnapshot s : all) {
                if (address.equals(s.address())) {
                    byQueue.computeIfAbsent(s.queueName(), k -> new ArrayList<>())
                            .add(s);
                }
            }
            List<DlqQueue> queues = new ArrayList<>();
            byQueue.forEach((queueName, rows) -> {
                List<DlqQueueDepth> perNode = rows.stream()
                        .map(r -> new DlqQueueDepth(
                                r.nodeId(), nodeNames.getOrDefault(r.nodeId(), "unknown"), r.messageCount()))
                        .toList();
                long totalDepth =
                        perNode.stream().mapToLong(DlqQueueDepth::depth).sum();
                queues.add(new DlqQueue(queueName, address, totalDepth, perNode));
            });
            addresses.add(new DlqAddress(address, entry.getValue(), queues));
        }
        return new DlqView(addresses, true);
    }

    private void acquire(UUID nodeId) {
        try {
            limiter.acquire(nodeId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE, "Timed out waiting for a per-node call permit.");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
