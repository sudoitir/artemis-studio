package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigViews.DocumentView;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request bodies for the declared broker configuration API (ADR-0067). */
public final class BrokerConfigRequests {

    private BrokerConfigRequests() {}

    @Schema(
            description =
                    "Save a new revision. expectedRevision is the revision that was edited; a stale one is refused")
    public record SaveDeclarationRequest(
            @NotNull @Schema(requiredMode = REQUIRED) DocumentView document,

            @Schema(
                    nullable = true,
                    description = "The revision this edit was made against; 0 or null for a first save")
            Integer expectedRevision,

            @Schema(nullable = true) String note) {}

    @Schema(description = "How the cluster's configuration is applied and what drift reports")
    public record ConfigureRequest(
            @Pattern(regexp = "STUDIO_MANAGED|CONFIG_MANAGED") @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"STUDIO_MANAGED", "CONFIG_MANAGED"})
            String applyMode,

            @Schema(requiredMode = REQUIRED) boolean reportUndeclared,
            @Schema(requiredMode = REQUIRED) List<String> undeclaredExclusions) {}

    @Schema(
            description =
                    "What an apply should consider. Every field is optional; the default is the current revision on every live node")
    public record ApplyRequest(
            @Schema(nullable = true, description = "Refused when it is not the current revision")
            Integer revision,

            @Schema(nullable = true, description = "Live nodes to target; empty for all")
            Set<UUID> nodeIds,

            @Schema(nullable = true, description = "The node to apply first")
            UUID canaryNodeId,

            @Schema(nullable = true, description = "Also remove settings and diverts Studio did not apply")
            Boolean removeUndeclared,

            @Schema(nullable = true, description = "High hazard identifiers the operator acknowledged")
            List<String> acknowledgedHazards,

            @Schema(
                    nullable = true,
                    description = "The planHash that was previewed; a real run refuses when it changed")
            String expectedPlanHash) {}
}
