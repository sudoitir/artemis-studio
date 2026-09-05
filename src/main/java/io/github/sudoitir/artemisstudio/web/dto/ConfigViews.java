package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * The broker configuration comparison API (ADR-0043). Read-only introspection: no
 * broker state changes and no audit event is written.
 *
 * <p>{@code @Schema} on every component so the generated OpenAPI document (and the
 * frontend's {@code schema.d.ts}) declares requiredness and nullability honestly
 * (ADR-0019): required unless marked {@code nullable = true}.
 */
public final class ConfigViews {

    private ConfigViews() {}

    /**
     * One side of the comparison.
     *
     * @param unavailableReason why this node could not be read; when set, no per-key
     *     drift is reported at all rather than a half-diff whose absent keys read as
     *     removals
     * @param reducedSurface true when the node is not serving and answered with less
     *     than the other side exposes — stated plainly instead of diffed
     */
    @Schema(description = "One node in a configuration comparison")
    public record ConfigSideView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) boolean available,
            @Schema(requiredMode = REQUIRED) boolean active,
            @Schema(requiredMode = REQUIRED) boolean reducedSurface,
            @Schema(nullable = true) String unavailableReason) {}

    /**
     * One compared key.
     *
     * @param status {@code SAME} | {@code DIFFERENT} | {@code ONLY_IN_LEFT} | {@code ONLY_IN_RIGHT}
     * @param statusWord the same thing as a phrase, so the UI never carries status by
     *     colour alone
     * @param classification {@code CONFIGURATION} | {@code EXPECTED} | {@code UNCLASSIFIED}
     */
    @Schema(description = "One configuration key, compared across both nodes")
    public record ConfigEntryView(
            @Schema(requiredMode = REQUIRED) String key,
            @Schema(nullable = true) String left,
            @Schema(nullable = true) String right,
            @Schema(requiredMode = REQUIRED) String status,
            @Schema(requiredMode = REQUIRED) String statusWord,
            @Schema(requiredMode = REQUIRED) String classification,
            @Schema(requiredMode = REQUIRED) boolean drift) {}

    @Schema(description = "One section of the comparison")
    public record ConfigSectionView(
            @Schema(requiredMode = REQUIRED) String section,
            @Schema(requiredMode = REQUIRED) String label,
            @Schema(requiredMode = REQUIRED) List<ConfigEntryView> entries,
            @Schema(requiredMode = REQUIRED) int driftCount) {}

    /**
     * @param comparable false when either side is unavailable, or when a passive node's
     *     reduced surface makes the comparison meaningless; the sections are then empty
     *     and {@code note} says why
     * @param matchesCompared how many address-setting match patterns were compared
     * @param matchesAvailable how many were known about; when it exceeds
     *     {@code matchesCompared} the cap applied, and the UI says so
     */
    @Schema(description = "Broker configuration compared across two nodes")
    public record ConfigDiffView(
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(requiredMode = REQUIRED) ConfigSideView left,
            @Schema(requiredMode = REQUIRED) ConfigSideView right,
            @Schema(requiredMode = REQUIRED) boolean comparable,
            @Schema(requiredMode = REQUIRED) List<ConfigSectionView> sections,
            @Schema(requiredMode = REQUIRED) int driftCount,
            @Schema(requiredMode = REQUIRED) int matchesCompared,
            @Schema(requiredMode = REQUIRED) int matchesAvailable,
            @Schema(nullable = true) String note) {}
}
