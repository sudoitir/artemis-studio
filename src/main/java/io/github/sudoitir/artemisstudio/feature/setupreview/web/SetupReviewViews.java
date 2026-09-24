package io.github.sudoitir.artemisstudio.feature.setupreview.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The setup review API (cluster-setup-review spec, ADR-0106). */
public final class SetupReviewViews {

    private SetupReviewViews() {}

    /**
     * A cluster's latest review.
     *
     * @param reviewedAt null when the cluster has never been reviewed
     * @param notice why an on-demand run did not happen (too soon, or already running), or null
     */
    public record SetupReviewView(
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(nullable = true) Instant reviewedAt,
            @Schema(requiredMode = REQUIRED) long durationMs,
            @Schema(requiredMode = REQUIRED) int nodesTotal,
            @Schema(requiredMode = REQUIRED) int nodesReviewed,
            @Schema(requiredMode = REQUIRED) boolean clusterEvaluated,
            @Schema(requiredMode = REQUIRED) List<ReviewedNodeView> nodes,
            @Schema(requiredMode = REQUIRED) List<SetupFindingView> findings,
            @Schema(requiredMode = REQUIRED) List<NotAssessedView> notAssessed,
            @Schema(requiredMode = REQUIRED) SeverityCountsView open,
            @Schema(requiredMode = REQUIRED) int accepted,
            @Schema(requiredMode = REQUIRED) int rulesInCatalogue,
            @Schema(nullable = true) String notice) {}

    /** @param reason why the node was not reviewed, or null when it was */
    public record ReviewedNodeView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) boolean live,
            @Schema(requiredMode = REQUIRED) boolean reviewed,
            @Schema(nullable = true) String reason) {}

    /** Findings not accepted as a known risk, by severity. */
    public record SeverityCountsView(
            @Schema(requiredMode = REQUIRED) int critical,
            @Schema(requiredMode = REQUIRED) int warning,
            @Schema(requiredMode = REQUIRED) int info) {}

    /**
     * @param subjectLabel the node's name, or "cluster"
     * @param stale the last review could not re-check it — its node did not answer
     * @param acceptance the operator's acceptance of it as a known risk, or null
     */
    public record SetupFindingView(
            @Schema(requiredMode = REQUIRED) String code,
            @Schema(requiredMode = REQUIRED) String category,
            @Schema(requiredMode = REQUIRED) String severity,
            @Schema(requiredMode = REQUIRED) String subject,
            @Schema(requiredMode = REQUIRED) String subjectLabel,
            @Schema(requiredMode = REQUIRED) String title,
            @Schema(requiredMode = REQUIRED) String impact,
            @Schema(requiredMode = REQUIRED) List<EvidenceView> evidence,
            @Schema(requiredMode = REQUIRED) String recommendation,
            @Schema(nullable = true) String snippet,
            @Schema(requiredMode = REQUIRED) List<String> caveats,
            @Schema(requiredMode = REQUIRED) boolean appliable,
            @Schema(requiredMode = REQUIRED) Instant firstSeenAt,
            @Schema(requiredMode = REQUIRED) Instant lastSeenAt,
            @Schema(requiredMode = REQUIRED) boolean stale,
            @Schema(nullable = true) AcceptanceView acceptance) {}

    public record EvidenceView(
            @Schema(nullable = true) String node,
            @Schema(requiredMode = REQUIRED) String key,
            @Schema(nullable = true) String value) {}

    /** @param active false once {@code expiresAt} has passed: the finding is open again */
    public record AcceptanceView(
            @Schema(requiredMode = REQUIRED) String reason,
            @Schema(requiredMode = REQUIRED) String acceptedBy,
            @Schema(requiredMode = REQUIRED) Instant createdAt,
            @Schema(nullable = true) Instant expiresAt,
            @Schema(requiredMode = REQUIRED) boolean active) {}

    public record NotAssessedView(
            @Schema(requiredMode = REQUIRED) String code,
            @Schema(requiredMode = REQUIRED) String subject,
            @Schema(requiredMode = REQUIRED) String subjectLabel,
            @Schema(nullable = true) String reason) {}

    /** Accept a finding as a known risk. {@code expiresAt} null: until revoked. */
    public record AcceptRiskRequest(
            @NotBlank String code,
            @NotBlank String subject,
            @NotBlank @Size(max = 1000) String reason,
            @Schema(nullable = true) Instant expiresAt) {}
}
