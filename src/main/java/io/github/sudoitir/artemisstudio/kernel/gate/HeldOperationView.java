package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A held operation as a provider sees it. Secret parameters are redacted; the sealed original never
 * leaves Studio.
 *
 * @param params the canonical parameters with secrets redacted
 * @param paramsHash the hex SHA-256 the request is bound to
 * @param approverId the approver once decided, or {@code null}
 * @param decisionReason the approver's reason, or {@code null}
 * @param outcomeDetail what happened when it ran or why it did not, or {@code null}
 */
@PluginApi
public record HeldOperationView(
        UUID id,
        String type,
        int typeVersion,
        HeldState state,
        ExecutionMode mode,
        Set<Trait> traits,
        OperationScope scope,
        String summary,
        List<DisplayRow> display,
        String params,
        String paramsHash,
        Effect effect,
        PolicyRef policy,
        Requester requester,
        String reason,
        UUID approverId,
        String approverUsername,
        String decisionReason,
        String outcomeDetail,
        Instant requestedAt,
        Instant expiresAt,
        Instant decidedAt,
        Instant finishedAt) {

    public HeldOperationView {
        traits = Set.copyOf(traits);
        display = List.copyOf(display);
    }
}
