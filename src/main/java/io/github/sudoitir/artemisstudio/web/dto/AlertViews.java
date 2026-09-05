package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The alerting read/write API (alerting spec, ADR-0035, ADR-0036). */
public final class AlertViews {

    private AlertViews() {}

    public record AlertRuleView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String kind,
            @Schema(nullable = true) String metric,
            @Schema(nullable = true) String comparator,
            @Schema(nullable = true) Double threshold,
            @Schema(nullable = true) String stateCondition,
            @Schema(requiredMode = REQUIRED) int forSeconds,
            @Schema(requiredMode = REQUIRED) String severity,
            @Schema(nullable = true) String scope,
            @Schema(requiredMode = REQUIRED) boolean enabled,
            @Schema(requiredMode = REQUIRED) List<UUID> channelIds,
            @Schema(requiredMode = REQUIRED) Instant createdAt,
            @Schema(requiredMode = REQUIRED) Instant updatedAt) {}

    /** {@code POST}/{@code PUT} a rule. Exactly one of the threshold fields or {@code stateCondition} must be set. */
    public record AlertRuleRequest(
            @NotBlank String name,
            @NotBlank String kind,
            String metric,
            String comparator,
            Double threshold,
            String stateCondition,
            int forSeconds,
            @NotBlank String severity,
            String scope,
            boolean enabled,
            List<UUID> channelIds) {}

    public record AlertFiringView(
            @Schema(requiredMode = REQUIRED) long seq,
            @Schema(requiredMode = REQUIRED) UUID ruleId,
            @Schema(requiredMode = REQUIRED) String ruleName,
            @Schema(requiredMode = REQUIRED) String subjectKey,
            @Schema(requiredMode = REQUIRED) String severity,
            @Schema(nullable = true) Double value,
            @Schema(requiredMode = REQUIRED) Instant startedAt,
            @Schema(nullable = true) Instant resolvedAt) {}

    public record AlertFiringPageView(
            @Schema(requiredMode = REQUIRED) List<AlertFiringView> items,
            @Schema(requiredMode = REQUIRED) long totalElements,
            @Schema(requiredMode = REQUIRED) int page,
            @Schema(requiredMode = REQUIRED) int size) {}

    public record NotificationChannelView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String kind,
            @Schema(requiredMode = REQUIRED) String config,
            @Schema(requiredMode = REQUIRED) boolean enabled,
            @Schema(requiredMode = REQUIRED) boolean hasSecret) {}

    /** {@code secret} is write-only: omit to leave an existing secret unchanged on update. */
    public record NotificationChannelRequest(
            @NotBlank String name, @NotBlank String kind, String config, String secret, boolean enabled) {}

    public record ClusterFiringCountView(
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(requiredMode = REQUIRED) long firing) {}
}
