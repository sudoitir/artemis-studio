package io.github.sudoitir.artemisstudio.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ProblemDetail;

/**
 * The SQL Console's wire shapes (ADR-0058). Provenance and incompleteness are
 * carried in the data rather than reconstructed by the client: a row says which
 * backend produced it, a result says which nodes did not answer and which bound it
 * hit, and a plan says what a query will cost before it runs.
 */
public final class SqlViews {

    private SqlViews() {}

    @Schema(description = "A query to plan or run.")
    public record SqlQueryRequest(String sql) {}

    @Schema(description = "What a query will do, worked out without contacting a broker.")
    public record PlanView(
            @Schema(description = "BROKER or INDEX — which backend will answer.")
            String source,

            @Schema(description = "The queue-and-node pairs the query will read.")
            List<TargetView> targets,

            @Schema(description = "The JMS selector the broker will evaluate, if any.")
            String selector,

            @Schema(description = "Whether any predicate forces messages to be read and examined.")
            boolean requiresScan,

            @Schema(description = "The predicates the broker evaluates. These cost nothing.")
            List<String> pushedDown,

            @Schema(description = "The predicates Studio evaluates. These cost a scan.")
            List<String> scanned,

            @Schema(description = "How many messages the query is expected to examine.")
            long estimatedMessagesExamined,

            @Schema(description = "The row limit that will actually apply, after the server cap.")
            int effectiveLimit,

            @Schema(description = "Everything true about this plan the operator has to be told.")
            List<NoticeView> notices) {}

    public record TargetView(UUID nodeId, String nodeName, String queueName, String address, long messageCount) {}

    @Schema(description = "Something true about a plan or result that changes how it should be read.")
    public record NoticeView(String kind, String detail) {}

    @Schema(description = "A message, and where it came from.")
    public record RowView(
            @Schema(description = "The node holding the message. Identity is (node, queue, messageId).")
            UUID nodeId,

            String nodeName,
            String queueName,
            String address,
            long messageId,
            int messageType,
            boolean durable,
            int priority,
            long timestamp,
            long expiration,
            long size,
            String jmsType,
            String correlationId,
            String groupId,
            String userId,
            String replyTo,
            String body,

            @Schema(description = "The management channel cut this body, so a body predicate may be a false negative.")
            boolean bodyTruncated,

            Map<String, Object> properties,

            @Schema(description = "BROKER or INDEX — an INDEX row may have been consumed since.")
            String source,

            @Schema(description = "When the index observed it. Null for a live broker row.")
            String observedAt,

            @Schema(description = "When the index last still saw it on its queue.")
            String lastSeenAt) {}

    @Schema(description = "What one node contributed, including nothing and why.")
    public record SqlNodeOutcomeView(
            UUID nodeId,
            String nodeName,
            String queueName,

            @Schema(description = "ANSWERED, FAILED or NOT_READ.")
            String status,

            long examined,
            long matched,

            @Schema(description = "JOLOKIA or CORE — the channel that served this node.")
            String servedBy,

            String detail) {}

    @Schema(description = "A limit the query ran into, which is why it stopped.")
    public record BoundView(String kind, long value) {}

    @Schema(description = "A finished query, without its rows: those arrived as row frames.")
    public record ResultView(
            @Schema(description = "Every target's outcome, including the ones that did not answer.")
            List<SqlNodeOutcomeView> nodes,

            @Schema(description = "Empty when the query ran to completion.")
            List<BoundView> boundsReached,

            List<NoticeView> notices,

            @Schema(description = "True when a bound was hit or a node did not answer.")
            boolean partial,

            PlanView plan) {}

    @Schema(description = "What a live tail has seen, and what it can prove it missed.")
    public record TailStatusView(
            @Schema(description = "Messages enqueued on the tailed queues since the tail started.")
            long enqueued,

            @Schema(description = "Rows this tail has delivered.")
            long shown,

            long polls,

            String lastPollAt,

            @Schema(
                    description = "True when the query has no predicate, so enqueued minus shown is"
                            + " exactly the number that passed through unobserved. When false the"
                            + " difference also holds messages that simply did not match.")
            boolean everyMessageMatches) {}

    /**
     * One frame on the query stream. Exactly one field is set, and the SSE event name
     * says which: {@code row}, {@code node}, {@code done}, {@code tail} or
     * {@code failed}. The failure frame is not named {@code error}: an
     * {@code EventSource} dispatches its own connection failures under that name, and
     * a client cannot then tell a refusal from a dropped socket. Declared as one record so every frame shape reaches the generated
     * client, which cannot describe a stream any other way. The plan is not a frame:
     * the console already has it from {@code /plan}, and the one that actually ran
     * comes back inside {@code done}.
     */
    @Schema(description = "One frame on the query stream. The SSE event name says which field is set.")
    public record StreamFrameView(
            RowView row, SqlNodeOutcomeView node, ResultView done, TailStatusView tail, ProblemDetail error) {}

    @Schema(description = "Which indexed row to look for on the live broker.")
    public record VerifyRequest(UUID nodeId, String queueName, long messageId, long timestamp) {}

    @Schema(description = "Whether an indexed row is still on the broker.")
    public record VerifyView(
            @Schema(
                    description = "PRESENT, GONE or UNKNOWN. UNKNOWN is not GONE: a read that could not"
                            + " settle the question is never reported as an absence.")
            String presence,

            String detail) {}

    // ---- index subscriptions -------------------------------------------

    @Schema(description = "A request to start capturing a queue's messages into the index.")
    public record IndexSubscriptionRequest(
            String queuePattern, Integer retentionDays, Long intervalMs, Boolean enabled) {}

    @Schema(description = "One index subscription and what it currently holds.")
    public record IndexSubscriptionView(
            UUID id,
            String queuePattern,
            int retentionDays,
            long intervalMs,
            String captureFrom,
            String createdAt,
            String createdBy,
            boolean enabled,

            @Schema(description = "How many messages this subscription is currently holding.")
            long messagesHeld,

            @Schema(description = "Approximate bytes of message payload held.")
            long bytesHeld,

            @Schema(description = "The oldest observation still held, or null when nothing is held.")
            String oldestObservedAt) {}
}
