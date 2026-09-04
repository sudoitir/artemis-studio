package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.persist.RrEventEntity;
import io.github.sudoitir.artemisstudio.persist.RrEventRepository;
import io.github.sudoitir.artemisstudio.persist.RrExpectationEntity;
import io.github.sudoitir.artemisstudio.persist.RrExpectationRepository;
import io.github.sudoitir.artemisstudio.persist.RrFlowEntity;
import io.github.sudoitir.artemisstudio.persist.RrFlowRepository;
import io.github.sudoitir.artemisstudio.security.ActorResolver;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.CreateExpectationRequest;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.ExpectationView;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.FlowPageView;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.FlowView;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.RrEventView;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.UpdateExpectationRequest;
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
 * Request-reply expectations (which addresses to trace, and how) and the read
 * side of reconstructed flows (request-reply-tracing spec). Every expectation
 * write is audited in the same transaction as the change (ADR-0002
 * non-negotiable #3).
 */
@Service
@RequiredArgsConstructor
public class RequestReplyService {

    private static final TypeReference<Map<String, Object>> DETAIL_TYPE = new TypeReference<>() {};

    private final RrExpectationRepository expectations;
    private final RrFlowRepository flows;
    private final RrEventRepository events;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final ObjectMapper mapper;

    // ---- expectations -------------------------------------------------

    @Transactional(readOnly = true)
    public List<ExpectationView> list(UUID clusterId) {
        return expectations.findByClusterIdOrderByRequestAddress(clusterId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public ExpectationView create(UUID clusterId, CreateExpectationRequest request) {
        AuditEventEntity audited = audit.begin(
                actorResolver.resolve(),
                "CREATE_RR_EXPECTATION",
                "RR_EXPECTATION",
                request.requestAddress(),
                clusterId,
                null,
                Map.of("requestAddress", request.requestAddress()),
                false);
        RrExpectationEntity entity = expectations.save(new RrExpectationEntity(
                clusterId,
                request.requestAddress(),
                blankToNull(request.replyAddress()),
                blankToNull(request.correlationProperty()),
                request.deadlineMs(),
                request.samplePerMin() > 0 ? request.samplePerMin() : 10,
                request.capturePayload()));
        audit.succeed(audited, 1);
        return toView(entity);
    }

    @Transactional
    public ExpectationView update(UUID clusterId, UUID expectationId, UpdateExpectationRequest request) {
        RrExpectationEntity entity = expectations
                .findById(expectationId)
                .filter(e -> e.getClusterId().equals(clusterId))
                .orElseThrow(() -> new NotFoundException("Request-reply expectation", expectationId));

        AuditEventEntity audited = audit.begin(
                actorResolver.resolve(),
                "UPDATE_RR_EXPECTATION",
                "RR_EXPECTATION",
                entity.getRequestAddress(),
                clusterId,
                null,
                Map.of("enabled", request.enabled()),
                false);

        entity.setReplyAddress(blankToNull(request.replyAddress()));
        entity.setCorrelationProperty(blankToNull(request.correlationProperty()));
        entity.setDeadlineMs(request.deadlineMs());
        entity.setSamplePerMin(request.samplePerMin() > 0 ? request.samplePerMin() : entity.getSamplePerMin());
        entity.setCapturePayload(request.capturePayload());
        entity.setEnabled(request.enabled());

        audit.succeed(audited, 1);
        return toView(entity);
    }

    @Transactional
    public void delete(UUID clusterId, UUID expectationId) {
        RrExpectationEntity entity = expectations
                .findById(expectationId)
                .filter(e -> e.getClusterId().equals(clusterId))
                .orElseThrow(() -> new NotFoundException("Request-reply expectation", expectationId));

        AuditEventEntity audited = audit.begin(
                actorResolver.resolve(),
                "DELETE_RR_EXPECTATION",
                "RR_EXPECTATION",
                entity.getRequestAddress(),
                clusterId,
                null,
                Map.of(),
                false);
        expectations.delete(entity);
        audit.succeed(audited, 1);
    }

    private ExpectationView toView(RrExpectationEntity e) {
        return new ExpectationView(
                e.getId(),
                e.getRequestAddress(),
                e.getReplyAddress(),
                e.getCorrelationProperty(),
                e.getDeadlineMs(),
                e.getSamplePerMin(),
                e.isCapturePayload(),
                e.isEnabled());
    }

    // ---- flows (read side) ---------------------------------------------

    @Transactional(readOnly = true)
    public FlowPageView flowPage(
            UUID clusterId,
            String state,
            String address,
            String correlationId,
            Instant from,
            Instant to,
            int page,
            int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 500);
        Page<RrFlowEntity> result = flows.findPage(
                clusterId,
                blankToNull(state),
                blankToNull(address),
                blankToNull(correlationId),
                from != null ? from : Instant.EPOCH,
                to != null ? to : Instant.parse("9999-12-31T23:59:59Z"),
                PageRequest.of(p - 1, s));
        return new FlowPageView(
                result.getContent().stream().map(f -> toFlowView(f, false)).toList(), result.getTotalElements(), p, s);
    }

    @Transactional(readOnly = true)
    public FlowView flow(UUID clusterId, UUID flowId) {
        RrFlowEntity entity = flows.findById(flowId)
                .filter(f -> f.getClusterId().equals(clusterId))
                .orElseThrow(() -> new NotFoundException("Request-reply flow", flowId));
        return toFlowView(entity, true);
    }

    private FlowView toFlowView(RrFlowEntity f, boolean withEvents) {
        return new FlowView(
                f.getId(),
                f.getClusterId(),
                f.getNodeId(),
                f.getRequestAddress(),
                f.getReplyDestination(),
                f.getReplyKind(),
                f.getState(),
                f.getCorrelationId(),
                f.getRequestedAt(),
                f.getDeadlineAt(),
                f.getRepliedAt(),
                f.getLatencyMs(),
                withEvents ? eventViews(f.getId()) : null);
    }

    private List<RrEventView> eventViews(UUID flowId) {
        return events.findByFlowIdOrderByTsAsc(flowId).stream()
                .map(this::toEventView)
                .toList();
    }

    private RrEventView toEventView(RrEventEntity e) {
        return new RrEventView(e.getSeq(), e.getTs(), e.getKind(), e.getNodeId(), parseDetail(e.getDetail()));
    }

    private Map<String, Object> parseDetail(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, DETAIL_TYPE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
