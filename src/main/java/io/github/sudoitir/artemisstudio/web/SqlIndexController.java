package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.persist.MessageIndexSubscriptionEntity;
import io.github.sudoitir.artemisstudio.sql.MessageIndexService;
import io.github.sudoitir.artemisstudio.sql.MessageIndexService.Subscription;
import io.github.sudoitir.artemisstudio.web.dto.SqlViews.IndexSubscriptionRequest;
import io.github.sudoitir.artemisstudio.web.dto.SqlViews.IndexSubscriptionView;
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
        return toView(index.create(
                clusterId,
                request.queuePattern(),
                request.intervalMs() == null ? 5000L : request.intervalMs(),
                request.retentionDays() == null ? 7 : request.retentionDays()));
    }

    @PatchMapping("/{id}")
    public IndexSubscriptionView update(
            @PathVariable UUID clusterId, @PathVariable UUID id, @RequestBody IndexSubscriptionRequest request) {
        return toView(index.update(clusterId, id, request.enabled(), request.intervalMs(), request.retentionDays()));
    }

    /** Deletes the subscription and everything it captured, and says how much that was. */
    @DeleteMapping("/{id}")
    public DeletedView delete(@PathVariable UUID clusterId, @PathVariable UUID id) {
        return new DeletedView(index.delete(clusterId, id));
    }

    @Schema(description = "How many captured messages the deletion destroyed.")
    public record DeletedView(long messagesDestroyed) {}

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
                iso(subscription.footprint().oldest()));
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
