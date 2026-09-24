package io.github.sudoitir.artemisstudio.feature.setupreview;

import io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence.FindingKey;
import io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence.SetupFindingEntity;
import io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence.SetupFindingRepository;
import io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence.SetupReviewEntity;
import io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence.SetupReviewRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes one review's result in its own transaction, after the broker reads (ADR-0015: network
 * calls never share a transaction with persistence).
 *
 * <p>Findings are replaced only for the subjects the run evaluated. A finding about a node that did
 * not answer keeps its last rendering and its {@code lastSeenAt}, which is what marks it stale; a
 * finding about a node that is no longer registered is removed, since nothing can ever re-check it.
 */
@Component
@RequiredArgsConstructor
class SetupReviewStore {

    private final SetupReviewRepository reviews;
    private final SetupFindingRepository findings;
    private final ObjectMapper mapper;

    @Transactional
    public void persist(UUID clusterId, List<NodeRead> reads, SetupRules.Result result, Instant now, long durationMs) {
        Map<FindingKey, SetupFindingEntity> existing = new HashMap<>();
        for (SetupFindingEntity row : findings.findByClusterId(clusterId)) {
            existing.put(new FindingKey(clusterId, row.getCode(), row.getSubject()), row);
        }
        for (Finding f : result.findings()) {
            FindingKey key = new FindingKey(clusterId, f.code(), f.subject());
            SetupFindingEntity row = existing.remove(key);
            if (row == null) {
                row = new SetupFindingEntity(clusterId, f.code(), f.subject(), now);
            }
            row.seen(f.severity().name(), f.category().name(), mapper.writeValueAsString(f), now);
            findings.save(row);
        }
        Set<String> registered = reads.stream().map(NodeRead::subject).collect(Collectors.toSet());
        for (SetupFindingEntity row : existing.values()) {
            boolean fixed = result.evaluated().contains(row.getSubject());
            boolean gone = row.getSubject().startsWith("node:") && !registered.contains(row.getSubject());
            if (fixed || gone) {
                findings.delete(row);
            }
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (NodeRead n : reads) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("nodeId", n.nodeId().toString());
            node.put("nodeName", n.nodeName());
            node.put("live", n.live());
            node.put("reviewed", n.readable());
            node.put(
                    "reason",
                    n.readable()
                            ? null
                            : n.manageable() ? n.unavailableReason() : "Studio has no management URL for this node.");
            nodes.add(node);
        }
        SetupReviewEntity review = reviews.findById(clusterId).orElseGet(() -> new SetupReviewEntity(clusterId));
        review.record(
                now,
                durationMs,
                reads.size(),
                (int) reads.stream().filter(NodeRead::readable).count(),
                mapper.writeValueAsString(nodes),
                mapper.writeValueAsString(result.notAssessed()),
                result.clusterEvaluated());
        reviews.save(review);
    }
}
