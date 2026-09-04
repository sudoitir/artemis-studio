package io.github.sudoitir.artemisstudio.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.domain.rr.FlowStateMachine;
import io.github.sudoitir.artemisstudio.domain.rr.FlowStateMachine.FlowContext;
import io.github.sudoitir.artemisstudio.domain.rr.FlowStateMachine.Transition;
import io.github.sudoitir.artemisstudio.domain.rr.Observation;
import io.github.sudoitir.artemisstudio.domain.rr.RrState;
import io.github.sudoitir.artemisstudio.persist.RrEventEntity;
import io.github.sudoitir.artemisstudio.persist.RrEventRepository;
import io.github.sudoitir.artemisstudio.persist.RrExpectationEntity;
import io.github.sudoitir.artemisstudio.persist.RrExpectationRepository;
import io.github.sudoitir.artemisstudio.persist.RrFlowEntity;
import io.github.sudoitir.artemisstudio.persist.RrFlowRepository;
import io.github.sudoitir.artemisstudio.sse.SseHub;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives {@code rr_flow} through its six states from normalised
 * {@link Observation}s, from either channel (design.md D2). Owns the reply join
 * (D3), deadline resolution (design.md), and the current-responder tracker the
 * deadline sweep and the creation path both need to tell {@code ORPHANED} from
 * {@code TIMED_OUT}.
 */
@Service
@Slf4j
public class RrCorrelator implements RrObservationSink {

    private final RrFlowRepository flows;
    private final RrEventRepository events;
    private final RrExpectationRepository expectations;
    private final RrMetrics metrics;
    private final SseHub sseHub;
    private final ObjectMapper mapper;
    private final int defaultDeadlineMs;
    private final int payloadCaptureBytes;

    /** {@code clusterId|address -> currently observed responder consumer, or null}. In-memory, address-scoped (not per-flow) — a request-reply address either has a responder or it doesn't. */
    private final Map<String, String> currentResponder = new ConcurrentHashMap<>();

    /** Dedupe hot path: {@code clusterId|address|messageId -> flowId} (design.md D5). Postgres stays authoritative — a cache miss just costs one extra query. */
    private final Cache<String, UUID> recentRequestFlow = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    public RrCorrelator(
            RrFlowRepository flows,
            RrEventRepository events,
            RrExpectationRepository expectations,
            RrMetrics metrics,
            SseHub sseHub,
            ObjectMapper mapper,
            ArtemisStudioProperties properties) {
        this.flows = flows;
        this.events = events;
        this.expectations = expectations;
        this.metrics = metrics;
        this.sseHub = sseHub;
        this.mapper = mapper;
        this.defaultDeadlineMs = properties.rr().defaultDeadlineMs();
        this.payloadCaptureBytes = properties.rr().payloadCaptureBytes();
    }

    @Override
    @Transactional
    public void accept(Observation observation) {
        switch (observation) {
            case Observation.RequestSeen r -> onRequestSeen(r);
            case Observation.ReplySeen r -> onReplySeen(r);
            case Observation.ResponderUp r -> onResponderUp(r);
            case Observation.ResponderDown r -> onResponderDown(r);
            case Observation.TempQueueUnbound r -> onTempQueueUnbound(r);
            case Observation.MessageExpired r -> onMessageExpired(r);
        }
    }

    private void onRequestSeen(Observation.RequestSeen r) {
        String dedupeKey = r.clusterId() + "|" + r.requestAddress() + "|" + r.messageId();
        if (recentRequestFlow.getIfPresent(dedupeKey) != null) {
            return;
        }
        Optional<RrFlowEntity> existing = flows.findByClusterIdAndRequestAddressAndRequestMessageId(
                r.clusterId(), r.requestAddress(), r.messageId());
        if (existing.isPresent()) {
            recentRequestFlow.put(dedupeKey, existing.get().getId());
            return;
        }

        RrExpectationEntity expectation = expectationFor(r.clusterId(), r.requestAddress());
        String replyKind = r.replyTo() != null ? "TEMP_QUEUE" : "SHARED_QUEUE";
        String destination =
                r.replyTo() != null ? r.replyTo() : (expectation != null ? expectation.getReplyAddress() : null);
        Instant deadline = deadlineAt(r, expectation);

        RrFlowEntity flow = new RrFlowEntity(
                r.clusterId(),
                r.nodeId(),
                r.requestAddress(),
                destination,
                replyKind,
                RrState.AWAITING_REPLY.name(),
                r.correlationId(),
                r.messageId(),
                r.at(),
                deadline);
        flow.setResponderConsumer(currentResponder.get(r.clusterId() + "|" + r.requestAddress()));
        flows.save(flow);
        recentRequestFlow.put(dedupeKey, flow.getId());

        boolean capturePayload = expectation != null && expectation.isCapturePayload();
        recordEvent(flow.getId(), r.nodeId(), "REQUEST_SEEN", r.at(), capturePayload ? detailOf(r) : null);
        sseHub.publish(r.clusterId(), "rr");
    }

    private void onReplySeen(Observation.ReplySeen r) {
        List<RrFlowEntity> matches =
                flows.findOpenMatches(r.clusterId(), r.replyDestination(), r.correlationId(), PageRequest.of(0, 1));
        if (matches.isEmpty()) {
            String replyKind = r.correlationId() != null ? "SHARED_QUEUE" : "TEMP_QUEUE";
            RrFlowEntity orphan = RrFlowEntity.orphanedReply(
                    r.clusterId(), r.nodeId(), r.replyDestination(), replyKind, r.correlationId(), r.at());
            flows.save(orphan);
            recordEvent(orphan.getId(), r.nodeId(), "ORPHANED_REPLY", r.at(), null);
            sseHub.publish(r.clusterId(), "rr");
            return;
        }

        RrFlowEntity flow = matches.getFirst();
        FlowContext ctx = new FlowContext(
                RrState.valueOf(flow.getState()),
                flow.getRequestedAt(),
                flow.getRequestMessageId(),
                flow.getReplyDestination());
        Optional<Transition> transition = FlowStateMachine.apply(ctx, r);
        if (transition.isEmpty()) {
            return;
        }
        Transition t = transition.get();
        flow.setState(t.next().name());
        flow.setRepliedAt(r.at());
        flow.setReplyMessageId(r.messageId());
        flow.setLatencyMs(t.latencyMs());
        flow.setObservedAt(r.at());
        flows.save(flow);

        if (t.latencyMs() != null) {
            metrics.recordCompletion(flow.getClusterId(), flow.getRequestAddress(), t.latencyMs());
        }
        RrExpectationEntity expectation =
                flow.getRequestAddress() == null ? null : expectationFor(flow.getClusterId(), flow.getRequestAddress());
        boolean capturePayload = expectation != null && expectation.isCapturePayload();
        recordEvent(flow.getId(), r.nodeId(), t.eventKind(), r.at(), capturePayload ? detailOf(r) : null);
        sseHub.publish(r.clusterId(), "rr");
    }

    private void onResponderUp(Observation.ResponderUp r) {
        currentResponder.put(r.clusterId() + "|" + r.requestAddress(), r.consumerName());
        // Backfill: a request observed before any responder existed created its flow with
        // responderConsumer null. If a responder now attaches while that flow is still
        // awaiting reply, the eventual timeout must be TIMED_OUT, not ORPHANED — "no
        // responder was ever observed" covers the flow's whole lifetime, not just the
        // instant it was created.
        for (RrFlowEntity flow : flows.findByClusterIdAndRequestAddressAndState(
                r.clusterId(), r.requestAddress(), RrState.AWAITING_REPLY.name())) {
            if (flow.getResponderConsumer() == null) {
                flow.setResponderConsumer(r.consumerName());
                flows.save(flow);
            }
        }
    }

    private void onResponderDown(Observation.ResponderDown r) {
        String key = r.clusterId() + "|" + r.requestAddress();
        if (r.remainingConsumers() == 0) {
            currentResponder.remove(key);
        }
        for (RrFlowEntity flow : flows.findByClusterIdAndRequestAddressAndState(
                r.clusterId(), r.requestAddress(), RrState.AWAITING_REPLY.name())) {
            FlowContext ctx = new FlowContext(
                    RrState.AWAITING_REPLY,
                    flow.getRequestedAt(),
                    flow.getRequestMessageId(),
                    flow.getReplyDestination());
            FlowStateMachine.apply(ctx, r).ifPresent(t -> applyTransition(flow, t, r.at()));
        }
    }

    private void onTempQueueUnbound(Observation.TempQueueUnbound r) {
        for (RrFlowEntity flow : flows.findByClusterIdAndReplyDestinationAndState(
                r.clusterId(), r.queueName(), RrState.AWAITING_REPLY.name())) {
            FlowContext ctx = new FlowContext(
                    RrState.AWAITING_REPLY,
                    flow.getRequestedAt(),
                    flow.getRequestMessageId(),
                    flow.getReplyDestination());
            FlowStateMachine.apply(ctx, r).ifPresent(t -> applyTransition(flow, t, r.at()));
        }
    }

    private void onMessageExpired(Observation.MessageExpired r) {
        Optional<RrFlowEntity> flow =
                flows.findByClusterIdAndRequestAddressAndRequestMessageId(r.clusterId(), r.address(), r.messageId());
        flow.filter(f -> RrState.AWAITING_REPLY.name().equals(f.getState())).ifPresent(f -> {
            FlowContext ctx = new FlowContext(
                    RrState.AWAITING_REPLY, f.getRequestedAt(), f.getRequestMessageId(), f.getReplyDestination());
            FlowStateMachine.apply(ctx, r).ifPresent(t -> applyTransition(f, t, r.at()));
        });
    }

    private void applyTransition(RrFlowEntity flow, Transition t, Instant at) {
        flow.setState(t.next().name());
        flow.setObservedAt(at);
        flows.save(flow);
        recordEvent(flow.getId(), flow.getNodeId(), t.eventKind(), at, null);
        sseHub.publish(flow.getClusterId(), "rr");
    }

    private Instant deadlineAt(Observation.RequestSeen r, RrExpectationEntity expectation) {
        if (r.expiration() > 0) {
            return Instant.ofEpochMilli(r.expiration());
        }
        if (expectation != null && expectation.getDeadlineMs() != null) {
            return r.at().plusMillis(expectation.getDeadlineMs());
        }
        return r.at().plusMillis(defaultDeadlineMs);
    }

    /**
     * Whether {@code address} is a currently-enabled traced request address for
     * this cluster. {@code activemq.notifications} fires consumer/binding events
     * for every address on the broker, traced or not — callers use this to avoid
     * tracking responder state, or matching a delivery as a reply, for addresses
     * nobody asked Studio to trace.
     */
    boolean isTracedRequestAddress(UUID clusterId, String address) {
        return expectations.findByClusterIdOrderByRequestAddress(clusterId).stream()
                .anyMatch(e -> e.isEnabled() && e.getRequestAddress().equals(address));
    }

    /** Whether some flow is still awaiting a reply on this exact temp-queue destination. */
    boolean hasOpenTempQueueFlow(UUID clusterId, String replyDestination) {
        return !flows.findByClusterIdAndReplyDestinationAndState(
                        clusterId, replyDestination, RrState.AWAITING_REPLY.name())
                .isEmpty();
    }

    private RrExpectationEntity expectationFor(UUID clusterId, String requestAddress) {
        return expectations.findByClusterIdOrderByRequestAddress(clusterId).stream()
                .filter(e -> e.getRequestAddress().equals(requestAddress))
                .findFirst()
                .orElse(null);
    }

    private void recordEvent(UUID flowId, UUID nodeId, String kind, Instant at, Map<String, Object> detail) {
        String json = detail == null || detail.isEmpty() ? null : mapper.writeValueAsString(detail);
        events.save(new RrEventEntity(flowId, nodeId, kind, at, json));
    }

    private Map<String, Object> detailOf(Observation.RequestSeen r) {
        return capturedPayload(r.bodyPreview());
    }

    private Map<String, Object> detailOf(Observation.ReplySeen r) {
        return capturedPayload(r.bodyPreview());
    }

    /** Truncates a captured body to {@code artemis-studio.rr.payload-capture-bytes} (design.md, bounded capture). */
    private Map<String, Object> capturedPayload(String bodyPreview) {
        if (bodyPreview == null) {
            return null;
        }
        boolean truncated = bodyPreview.length() > payloadCaptureBytes;
        String stored = truncated ? bodyPreview.substring(0, payloadCaptureBytes) : bodyPreview;
        return truncated ? Map.of("bodyPreview", stored, "truncated", true) : Map.of("bodyPreview", stored);
    }
}
