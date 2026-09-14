package io.github.sudoitir.artemisstudio.feature.sql.web;

import io.github.sudoitir.artemisstudio.feature.sql.CaptureMode;
import io.github.sudoitir.artemisstudio.feature.sql.MessageIndexService;
import io.github.sudoitir.artemisstudio.feature.sql.MessageIndexService.Subscription;
import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageCaptureNodeEntity;
import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import io.github.sudoitir.artemisstudio.feature.sql.web.SqlViews.CaptureNodeView;
import io.github.sudoitir.artemisstudio.feature.sql.web.SqlViews.IndexSubscriptionRequest;
import io.github.sudoitir.artemisstudio.feature.sql.web.SqlViews.IndexSubscriptionView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Index subscriptions — which queues Studio keeps a copy of, for how long
 * (ADR-0059).
 *
 * <p>Every write here needs the settings write permission and is audited: a
 * subscription is a decision to store application payload, not a query. Reading the
 * list needs only message read, because it is a statement about what already exists.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/sql/index")
@RequiredArgsConstructor
public class SqlIndexController {

    private final MessageIndexService index;

    @GetMapping
    public List<IndexSubscriptionView> list(@PathVariable UUID clusterId) {
        return index.list(clusterId).stream().map(SqlIndexController::toView).toList();
    }

    @PostMapping
    public IndexSubscriptionView create(@PathVariable UUID clusterId, @RequestBody IndexSubscriptionRequest request) {
        return toView(index.create(clusterId, toSpec(request)));
    }

    @PatchMapping("/{id}")
    public IndexSubscriptionView update(
            @PathVariable UUID clusterId, @PathVariable UUID id, @RequestBody IndexSubscriptionRequest request) {
        return toView(index.update(clusterId, id, toSpec(request)));
    }

    /**
     * Deletes the subscription and everything it captured, and says how much that was. The
     * taps it owned are swept by the reconciler, under the cluster lock, once the deletion has
     * committed — so a node unreachable now is cleaned on its next pass.
     */
    @DeleteMapping("/{id}")
    public DeletedView delete(@PathVariable UUID clusterId, @PathVariable UUID id) {
        return new DeletedView(index.delete(clusterId, id).messagesDestroyed());
    }

    @Schema(description = "How many captured messages the deletion destroyed.")
    public record DeletedView(long messagesDestroyed) {}

    private static MessageIndexService.Spec toSpec(IndexSubscriptionRequest request) {
        return new MessageIndexService.Spec(
                request.queuePattern(),
                request.intervalMs(),
                request.retentionDays(),
                request.enabled(),
                request.mode() == null
                        ? null
                        : CaptureMode.valueOf(request.mode().toUpperCase()),
                request.ringSize(),
                request.filterString(),
                request.maxBytes(),
                request.maxRate(),
                request.bodyCapBytes());
    }

    private static IndexSubscriptionView toView(Subscription subscription) {
        MessageIndexSubscriptionEntity entity = subscription.entity();
        return new IndexSubscriptionView(
                entity.getId(),
                entity.getQueuePattern(),
                entity.getRetentionDays(),
                entity.getIntervalMs(),
                iso(entity.getCaptureFrom()),
                iso(entity.getCreatedAt()),
                entity.getCreatedBy(),
                entity.isEnabled(),
                subscription.footprint().messages(),
                subscription.footprint().payloadBytes(),
                iso(subscription.footprint().oldest()),
                subscription.notCapturing(),
                subscription.backlogInProgress(),
                entity.getMode().name(),
                entity.getRingSize(),
                entity.getFilterString(),
                entity.getMaxBytes(),
                entity.getMaxRate(),
                entity.getBodyCapBytes(),
                subscription.nodes().stream()
                        .map(SqlIndexController::toNodeView)
                        .toList());
    }

    private static CaptureNodeView toNodeView(MessageIndexService.CaptureNode node) {
        MessageCaptureNodeEntity state = node.state();
        return new CaptureNodeView(
                state.getNodeId(),
                node.nodeName(),
                state.getCaptureState().name(),
                state.getCaptureDetail(),
                iso(state.getCapturedFrom()),
                state.getDroppedEstimate());
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
