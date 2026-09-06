package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.rr.ReplyAddressResolver;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.persist.RrEventEntity;
import io.github.sudoitir.artemisstudio.persist.RrEventRepository;
import io.github.sudoitir.artemisstudio.persist.RrExpectationEntity;
import io.github.sudoitir.artemisstudio.persist.RrExpectationRepository;
import io.github.sudoitir.artemisstudio.persist.RrFlowEntity;
import io.github.sudoitir.artemisstudio.persist.RrFlowRepository;
import io.github.sudoitir.artemisstudio.security.ActorResolver;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.CreateExpectationRequest;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.ExpectationView;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.FlowPageView;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.FlowView;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.RrEventView;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.UpdateExpectationRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final ClusterAccessGuard clusterAccess;
    private final ReplyAddressResolver replyAddresses;

    // ---- expectations -------------------------------------------------

    @Transactional(readOnly = true)
    public List<ExpectationView> list(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return expectations.findByClusterIdOrderByRequestAddress(clusterId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public ExpectationView create(UUID clusterId, CreateExpectationRequest request) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_WRITE);
        // Checked before the audit row is opened: letting the unique constraint fire
        // instead would mark the transaction rollback-only, discarding the audit row
        // with it, and surface as an unmapped 500 the operator cannot act on.
        if (expectations.existsByClusterIdAndRequestAddress(clusterId, request.requestAddress())) {
            throw new ConflictException(
                    "duplicate-rr-expectation",
                    "'" + request.requestAddress()
                            + "' is already traced on this cluster. Edit the existing expectation instead.");
        }
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
                normaliseReplyAddresses(request.replyAddresses()),
                blankToNull(request.correlationProperty()),
                request.deadlineMs(),
                request.samplePerMin() > 0 ? request.samplePerMin() : 10,
                request.capturePayload()));
        audit.succeed(audited, 1);
        return toView(entity);
    }

    @Transactional
    public ExpectationView update(UUID clusterId, UUID expectationId, UpdateExpectationRequest request) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_WRITE);
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

        entity.setReplyAddresses(normaliseReplyAddresses(request.replyAddresses()));
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
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_WRITE);
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

    /**
     * Blanks dropped, duplicates collapsed, declaration order preserved. Null and
     * empty collapse to the same empty list on purpose: both spell the temporary-reply-queue
     * pattern, and keeping two spellings of one state would force every reader to
     * handle both (design.md, D3).
     */
    static List<String> normaliseReplyAddresses(List<String> declared) {
        if (declared == null) {
            return List.of();
        }
        return declared.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private ExpectationView toView(RrExpectationEntity e) {
        ReplyAddressResolver.Resolution resolved = replyAddresses.resolve(e.getClusterId(), e);
        return new ExpectationView(
                e.getId(),
                e.getRequestAddress(),
                e.getReplyAddresses(),
                resolved.addresses(),
                resolved.capped(),
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
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
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
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
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
