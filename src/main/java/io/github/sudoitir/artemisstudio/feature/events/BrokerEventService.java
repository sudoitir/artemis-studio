package io.github.sudoitir.artemisstudio.feature.events;

import io.github.sudoitir.artemisstudio.feature.events.internal.persistence.BrokerEventEntity;
import io.github.sudoitir.artemisstudio.feature.events.internal.persistence.BrokerEventRepository;
import io.github.sudoitir.artemisstudio.feature.events.web.EventViews.BrokerEventPageView;
import io.github.sudoitir.artemisstudio.feature.events.web.EventViews.BrokerEventView;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.platform.governance.ContentPolicy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Read side of the broker-event history (ADR-0028): filtered, paged, newest
 * first, with the current dropped-event count and the oldest retained event so
 * overflow is visible in the UI.
 */
@Service
@RequiredArgsConstructor
public class BrokerEventService {

    private static final TypeReference<Map<String, Object>> PROPS_TYPE = new TypeReference<>() {};

    private final BrokerEventRepository events;
    private final BrokerEventWriter writer;
    private final ObjectMapper mapper;

    /**
     * Guards {@link #page} and {@link #get} only. {@link #since} is reached solely from
     * {@code StreamController}, which applies the same check before subscribing.
     */
    private final ClusterAccessGuard clusterAccess;

    /** Notification props carry filter strings and user-supplied names; the detectors run over them. */
    private final ContentPolicy contentPolicy;

    @Transactional(readOnly = true)
    public BrokerEventPageView page(UUID clusterId, BrokerEventQuery query) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        Page<BrokerEventEntity> result = events.findPage(
                clusterId,
                blankToNull(query.type()),
                query.nodeId(),
                blankToNull(query.address()),
                query.from() != null ? query.from() : Instant.EPOCH,
                query.to() != null ? query.to() : Instant.parse("9999-12-31T23:59:59Z"),
                PageRequest.of(query.page() - 1, query.size()));
        return new BrokerEventPageView(
                result.getContent().stream().map(this::toView).toList(),
                result.getNumber() + 1,
                result.getSize(),
                result.getTotalElements(),
                result.hasNext(),
                writer.droppedFor(clusterId),
                events.oldestRetained(clusterId));
    }

    @Transactional(readOnly = true)
    public BrokerEventView get(UUID clusterId, long seq) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return events.findByClusterIdAndSeq(clusterId, seq)
                .map(this::toView)
                .orElseThrow(() -> new NotFoundException("event", seq));
    }

    /**
     * Recent events of one type for a cluster, unguarded, for scheduled evaluation.
     *
     * <p>A scrape-driven job runs with no authenticated principal, so a permission check
     * there protects nothing and fails as a 404 about a cluster that plainly exists. The
     * guard stays on {@link #page}, which is the request path. Used by the consumer-health
     * verdict to find the broker's own {@code CONSUMER_SLOW} notifications (ADR-0089).
     *
     * <p><b>Never call this from a request path.</b>
     */
    @Transactional(readOnly = true)
    public List<BrokerEventView> recentOfType(UUID clusterId, String type, Instant from, Instant to, int limit) {
        return events
                .findPage(
                        clusterId,
                        blankToNull(type),
                        null,
                        null,
                        from != null ? from : Instant.EPOCH,
                        to != null ? to : Instant.parse("9999-12-31T23:59:59Z"),
                        PageRequest.of(0, Math.clamp(limit, 1, 500)))
                .getContent()
                .stream()
                .map(this::toView)
                .toList();
    }

    /**
     * Bounded replay for a reconnecting SSE client: the {@code cap} most recent events after
     * {@code lastSeq}, oldest first, so the replay runs straight on into live delivery. A client
     * that missed more is told to resync by the stream.
     */
    @Transactional(readOnly = true)
    public List<BrokerEventView> since(UUID clusterId, long lastSeq, int cap) {
        return events
                .findByClusterIdAndSeqGreaterThanOrderBySeqDesc(
                        clusterId, lastSeq, PageRequest.of(0, Math.clamp(cap, 1, 1000)))
                .reversed()
                .stream()
                .map(this::toView)
                .toList();
    }

    public BrokerEventView toView(BrokerEventEntity e) {
        return new BrokerEventView(
                e.getSeq(),
                e.getOccurredAt(),
                e.getReceivedAt(),
                e.getType(),
                e.getAddress(),
                e.getRoutingName(),
                e.getConsumerName(),
                e.getSessionName(),
                e.getConnectionName(),
                e.getRemoteAddress(),
                e.getUsername(),
                e.getNodeId(),
                parseProps(e.getProps()));
    }

    private Map<String, Object> parseProps(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> props;
        try {
            props = mapper.readValue(json, PROPS_TYPE);
        } catch (RuntimeException _) {
            return Map.of();
        }
        Map<String, Object> governed = new LinkedHashMap<>();
        props.forEach((key, value) ->
                governed.put(key, value instanceof String text ? contentPolicy.governText(text) : value));
        return governed;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
