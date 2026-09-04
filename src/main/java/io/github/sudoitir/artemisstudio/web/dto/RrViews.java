package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The request-reply tracing API (request-reply-tracing spec). */
public final class RrViews {

    private RrViews() {}

    public record ExpectationView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String requestAddress,
            @Schema(nullable = true) String replyAddress,
            @Schema(nullable = true) String correlationProperty,
            @Schema(nullable = true) Integer deadlineMs,
            @Schema(requiredMode = REQUIRED) int samplePerMin,
            @Schema(requiredMode = REQUIRED) boolean capturePayload,
            @Schema(requiredMode = REQUIRED) boolean enabled) {}

    public record CreateExpectationRequest(
            @NotBlank String requestAddress,
            String replyAddress,
            String correlationProperty,
            @Min(1) Integer deadlineMs,
            @Min(1) int samplePerMin,
            boolean capturePayload) {}

    public record UpdateExpectationRequest(
            String replyAddress,
            String correlationProperty,
            @Min(1) Integer deadlineMs,
            @Min(1) int samplePerMin,
            boolean capturePayload,
            boolean enabled) {}

    public record RrEventView(
            @Schema(requiredMode = REQUIRED) long seq,
            @Schema(requiredMode = REQUIRED) Instant ts,
            @Schema(requiredMode = REQUIRED) String kind,
            @Schema(nullable = true) UUID nodeId,
            @Schema(nullable = true) Map<String, Object> detail) {}

    public record FlowView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(nullable = true) UUID nodeId,
            @Schema(nullable = true) String requestAddress,
            @Schema(nullable = true) String replyDestination,
            @Schema(requiredMode = REQUIRED) String replyKind,
            @Schema(requiredMode = REQUIRED) String state,
            @Schema(nullable = true) String correlationId,
            @Schema(requiredMode = REQUIRED) Instant requestedAt,
            @Schema(nullable = true) Instant deadlineAt,
            @Schema(nullable = true) Instant repliedAt,
            @Schema(nullable = true) Long latencyMs,
            @Schema(nullable = true) List<RrEventView> events) {}

    public record FlowPageView(
            @Schema(requiredMode = REQUIRED) List<FlowView> data,
            @Schema(requiredMode = REQUIRED) long count,
            @Schema(requiredMode = REQUIRED) int page,
            @Schema(requiredMode = REQUIRED) int pageSize) {}

    public record AddressStatsView(
            @Schema(requiredMode = REQUIRED) String address,
            @Schema(requiredMode = REQUIRED) long inFlight,
            @Schema(nullable = true) Long oldestInFlightMs,
            @Schema(requiredMode = REQUIRED) long completed,
            @Schema(requiredMode = REQUIRED) long timedOut,
            @Schema(requiredMode = REQUIRED) long orphaned,
            @Schema(requiredMode = REQUIRED) long responderDropped,
            @Schema(nullable = true) Double p50Ms,
            @Schema(nullable = true) Double p95Ms,
            @Schema(nullable = true) Double p99Ms,
            @Schema(requiredMode = REQUIRED) boolean sampled,
            @Schema(nullable = true) Double coverageRatio,
            @Schema(requiredMode = REQUIRED) long windowMs) {}

    public record StatsResponse(
            @Schema(requiredMode = REQUIRED) List<AddressStatsView> addresses) {}
}
