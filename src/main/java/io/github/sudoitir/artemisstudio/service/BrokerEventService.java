package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.persist.BrokerEventEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerEventRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerEventWriter;
import io.github.sudoitir.artemisstudio.web.dto.EventViews.BrokerEventPageView;
import io.github.sudoitir.artemisstudio.web.dto.EventViews.BrokerEventView;
import java.time.Instant;
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

    @Transactional(readOnly = true)
    public BrokerEventPageView page(
            UUID clusterId, String type, UUID nodeId, String address, Instant from, Instant to, int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 500);
        Page<BrokerEventEntity> result = events.findPage(
                clusterId,
                blankToNull(type),
                nodeId,
                blankToNull(address),
                from != null ? from : Instant.EPOCH,
                to != null ? to : Instant.parse("9999-12-31T23:59:59Z"),
                PageRequest.of(p - 1, s));
        return new BrokerEventPageView(
                result.getContent().stream().map(this::toView).toList(),
                result.getTotalElements(),
                p,
                s,
                writer.droppedFor(clusterId),
                events.oldestRetained(clusterId));
    }

    /** Bounded replay for a reconnecting SSE client (slice 3). */
    @Transactional(readOnly = true)
    public List<BrokerEventView> since(UUID clusterId, long lastSeq, int cap) {
        return events
                .findByClusterIdAndSeqGreaterThanOrderBySeqAsc(
                        clusterId, lastSeq, PageRequest.of(0, Math.min(Math.max(cap, 1), 1000)))
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
        try {
            return mapper.readValue(json, PROPS_TYPE);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
