package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The audit-log API (non-negotiable #3). Nothing secret appears here — credentials are never audited. */
public final class AuditViews {

    private AuditViews() {}

    public record AuditEventView(
            @Schema(requiredMode = REQUIRED) Instant ts,
            @Schema(nullable = true) String username,
            @Schema(nullable = true) String sourceIp,
            @Schema(nullable = true) String requestId,
            @Schema(requiredMode = REQUIRED) String action,
            @Schema(nullable = true) String targetType,
            @Schema(nullable = true) String targetName,
            @Schema(nullable = true) Long affectedCount,
            @Schema(requiredMode = REQUIRED) String outcome,
            @Schema(requiredMode = REQUIRED) boolean dryRun,
            @Schema(nullable = true) String params,
            @Schema(nullable = true) String error,
            @Schema(nullable = true) UUID nodeId) {}

    public record AuditPageView(
            @Schema(requiredMode = REQUIRED) List<AuditEventView> data,
            @Schema(requiredMode = REQUIRED) long count,
            @Schema(requiredMode = REQUIRED) int page,
            @Schema(requiredMode = REQUIRED) int pageSize) {}
}
