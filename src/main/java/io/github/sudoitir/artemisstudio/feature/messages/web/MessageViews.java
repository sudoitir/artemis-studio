package io.github.sudoitir.artemisstudio.feature.messages.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.artemisstudio.platform.governance.GovernanceViews.RedactionView;
import io.github.sudoitir.artemisstudio.platform.governance.GovernanceViews.WithheldView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Phase 3 message API (ADR-0021). Message I/O is Jolokia-only: bodies are
 * carried as text and the broker truncates oversized values, disclosed per
 * message via {@code bodyTruncated} + {@code observedLimitBytes}. Every header,
 * property and body value is governed by the content policy (ADR-0075): a
 * sensitive value is its marker unless the caller holds clear access, and
 * {@code redactions} / {@code withheld} say which values those are.
 *
 * <p>{@code @Schema} on every component so the generated OpenAPI document (and
 * the frontend's {@code schema.d.ts}) declares requiredness and nullability
 * honestly (ADR-0019): required unless marked {@code nullable = true}.
 */
public final class MessageViews {

    private MessageViews() {}

    /** One row in the message grid. */
    public record MessageSummaryView(
            @Schema(requiredMode = REQUIRED) long messageId,
            @Schema(requiredMode = REQUIRED) int type,
            @Schema(requiredMode = REQUIRED) boolean durable,
            @Schema(requiredMode = REQUIRED) int priority,
            @Schema(requiredMode = REQUIRED) long timestamp,
            @Schema(requiredMode = REQUIRED) long expiration,
            @Schema(requiredMode = REQUIRED) long size,
            @Schema(nullable = true) String groupId,
            @Schema(nullable = true) String correlationId,
            @Schema(nullable = true) String bodyPreview,
            @Schema(requiredMode = REQUIRED) boolean bodyTruncated,
            @Schema(requiredMode = REQUIRED) int propertyCount,
            @Schema(requiredMode = REQUIRED) List<RedactionView> redactions) {}

    /** One message, expanded: the full header set, the typed property maps, and the body. */
    public record MessageDetailView(
            @Schema(requiredMode = REQUIRED) long messageId,
            @Schema(requiredMode = REQUIRED) int type,
            @Schema(requiredMode = REQUIRED) boolean durable,
            @Schema(requiredMode = REQUIRED) int priority,
            @Schema(requiredMode = REQUIRED) long timestamp,
            @Schema(requiredMode = REQUIRED) long expiration,
            @Schema(requiredMode = REQUIRED) long size,
            @Schema(nullable = true) String groupId,
            @Schema(nullable = true) String correlationId,
            @Schema(nullable = true) String userId,
            @Schema(nullable = true) String body,
            @Schema(requiredMode = REQUIRED) String bodyEncoding,
            @Schema(nullable = true) String contentType,
            @Schema(requiredMode = REQUIRED) boolean bodyTruncated,
            @Schema(nullable = true) Integer observedLimitBytes,
            @Schema(requiredMode = REQUIRED) String transport,
            @Schema(requiredMode = REQUIRED) UUID node,
            @Schema(requiredMode = REQUIRED) Map<String, String> stringProperties,
            @Schema(requiredMode = REQUIRED) Map<String, Long> intProperties,
            @Schema(requiredMode = REQUIRED) Map<String, Long> longProperties,
            @Schema(requiredMode = REQUIRED) Map<String, Double> doubleProperties,
            @Schema(requiredMode = REQUIRED) Map<String, Boolean> booleanProperties,
            @Schema(requiredMode = REQUIRED) List<RedactionView> redactions,
            @Schema(requiredMode = REQUIRED) List<WithheldView> withheld) {}

    /**
     * A page of messages. {@code node} echoes the endpoint the page was read
     * from; {@code transport} is {@code CORE} or {@code JOLOKIA} — the channel
     * that actually served this page (a Core browse falls back to Jolokia on a
     * deep page).
     */
    public record MessagePageView(
            @Schema(requiredMode = REQUIRED) List<MessageSummaryView> data,

            @Schema(
                    nullable = true,
                    description = "The broker's own message count; null when it could not be obtained, never"
                            + " a guess — see countUnavailable.")
            Long count,

            @Schema(nullable = true, description = "Why count is null. Null whenever count is present.")
            String countUnavailable,

            @Schema(requiredMode = REQUIRED) int page,
            @Schema(requiredMode = REQUIRED) int pageSize,
            @Schema(requiredMode = REQUIRED) UUID node,
            @Schema(requiredMode = REQUIRED) String transport) {}

    /** The result of a mutation: the broker's own affected count (not a dry-run estimate). */
    public record AffectedView(
            @Schema(requiredMode = REQUIRED) long affectedCount,
            @Schema(requiredMode = REQUIRED) boolean dryRun,
            @Schema(requiredMode = REQUIRED) UUID node) {}

    /**
     * An operation by ids that stopped part-way. Some messages were acted on, so this is never
     * reported as a plain failure: the operator needs to know what already changed.
     *
     * @param affectedCount how many were acted on
     * @param notDone every id that was not acted on: refused by the broker, or never sent after the failure
     * @param error why it stopped
     */
    public record PartialView(
            @Schema(requiredMode = REQUIRED) long affectedCount,
            @Schema(requiredMode = REQUIRED) java.util.List<Long> notDone,
            @Schema(requiredMode = REQUIRED) String error,
            @Schema(requiredMode = REQUIRED) boolean partial,
            @Schema(requiredMode = REQUIRED) UUID node) {}

    /**
     * A point-in-time estimate from the broker for a destructive action, before it runs.
     *
     * @param affectedCount broker-side estimate ({@code countMessages(filter)} / id count / {@code MessageCount})
     * @param cap the current {@code safety.bulk-cap}
     * @param overCap whether {@code affectedCount} exceeds the cap (UI must gate an override behind typed confirmation)
     */
    public record DryRunView(
            @Schema(requiredMode = REQUIRED) long affectedCount,
            @Schema(requiredMode = REQUIRED) long cap,
            @Schema(requiredMode = REQUIRED) boolean overCap,
            @Schema(requiredMode = REQUIRED) UUID node) {}

    // ---- DLQ (Slice 9) --------------------------------------------------

    public record DlqQueueDepth(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) long depth) {}

    public record DlqQueue(
            @Schema(requiredMode = REQUIRED) String queueName,
            @Schema(requiredMode = REQUIRED) String address,
            @Schema(requiredMode = REQUIRED) long totalDepth,
            @Schema(requiredMode = REQUIRED) List<DlqQueueDepth> perNode) {}

    public record DlqAddress(
            @Schema(requiredMode = REQUIRED) String address,
            @Schema(requiredMode = REQUIRED) String kind,
            @Schema(requiredMode = REQUIRED) List<DlqQueue> queues) {}

    public record DlqView(
            @Schema(requiredMode = REQUIRED) List<DlqAddress> addresses,
            @Schema(requiredMode = REQUIRED) boolean settingsAvailable) {}
}
