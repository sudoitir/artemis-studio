package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.artemisstudio.service.LifecycleOutcome;
import io.github.sudoitir.artemisstudio.service.LifecycleOutcome.NodeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * The queue and address lifecycle API's responses (ADR-0049).
 *
 * <p>A lifecycle command targets a cluster and fans out to its live nodes, so the
 * response is a per-node list rather than a count — the shape the UI needs to show
 * a partial application as a partial application rather than as a success or a
 * failure.
 *
 * <p>{@code @Schema} on every component so the generated OpenAPI document (and the
 * frontend's {@code schema.d.ts}) declares requiredness and nullability honestly
 * (ADR-0019): required unless marked {@code nullable = true}.
 */
public final class LifecycleViews {

    private LifecycleViews() {}

    /** What happened on one node. */
    public record NodeOutcomeView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "WOULD_APPLY on a preview; APPLIED when the node was changed; ALREADY when it was"
                            + " already in the requested state; SKIPPED_NOT_LIVE when the node was not"
                            + " live and never received the command; FAILED when it refused.",
                    allowableValues = {"WOULD_APPLY", "APPLIED", "ALREADY", "SKIPPED_NOT_LIVE", "FAILED"})
            String status,

            @Schema(nullable = true, description = "Messages destroyed, or that would be destroyed, on this node.")
            Long affected,

            @Schema(nullable = true, description = "Why this node failed.")
            String error) {}

    /**
     * One lifecycle command's result.
     *
     * @param partial whether the command reached some nodes and not others — the
     *     single flag the UI needs to distinguish a partial application without
     *     reading every row
     */
    public record LifecycleOutcomeView(
            @Schema(requiredMode = REQUIRED, description = "Whether this was a preview that mutated nothing.")
            boolean dryRun,

            @Schema(requiredMode = REQUIRED, description = "The bulk safety cap in force.")
            long cap,

            @Schema(requiredMode = REQUIRED, description = "Whether the summed estimate exceeds the cap.")
            boolean overCap,

            @Schema(requiredMode = REQUIRED, description = "Whether some nodes applied the command and others did not.")
            boolean partial,

            @Schema(requiredMode = REQUIRED, description = "Total messages destroyed, or that would be.")
            long totalAffected,

            @Schema(requiredMode = REQUIRED) List<NodeOutcomeView> nodes) {

        /**
         * The wire shape of one fan-out result. {@code partial} is computed here
         * rather than in each client so every one of them agrees on what "landed
         * unevenly" means: at least one node changed or was already in the requested
         * state, and at least one did not receive it or refused it.
         */
        public static LifecycleOutcomeView of(LifecycleOutcome outcome) {
            boolean anySettled = outcome.nodes().stream()
                    .anyMatch(n -> n.status() == NodeStatus.APPLIED || n.status() == NodeStatus.ALREADY);
            boolean anyNot = outcome.nodes().stream()
                    .anyMatch(n -> n.status() == NodeStatus.FAILED || n.status() == NodeStatus.SKIPPED_NOT_LIVE);
            return new LifecycleOutcomeView(
                    outcome.dryRun(),
                    outcome.cap(),
                    outcome.overCap(),
                    !outcome.dryRun() && anySettled && anyNot,
                    outcome.totalAffected(),
                    outcome.nodes().stream()
                            .map(n -> new NodeOutcomeView(
                                    n.nodeId(), n.nodeName(), n.status().name(), n.affected(), n.error()))
                            .toList());
        }
    }
}
