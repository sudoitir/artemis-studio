package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.gate.AuthKind;
import io.github.sudoitir.artemisstudio.kernel.gate.DisplayRow;
import io.github.sudoitir.artemisstudio.kernel.gate.Effect;
import io.github.sudoitir.artemisstudio.kernel.gate.ExecutionMode;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldOperationView;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldState;
import io.github.sudoitir.artemisstudio.kernel.gate.OperationScope;
import io.github.sudoitir.artemisstudio.kernel.gate.PolicyRef;
import io.github.sudoitir.artemisstudio.kernel.gate.Requester;
import io.github.sudoitir.artemisstudio.kernel.gate.Trait;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** One {@code held_operation} row as {@link HeldStore} reads it. */
record HeldRow(
        UUID id,
        String type,
        int typeVersion,
        HeldState state,
        ExecutionMode mode,
        AuthKind authKind,
        String providerId,
        UUID requesterId,
        String requesterUsername,
        UUID tokenId,
        UUID approverId,
        String approverUsername,
        String summary,
        String reason,
        String approverHint,
        String decisionReason,
        String outcomeDetail,
        Set<Trait> traits,
        String params,
        List<DisplayRow> display,
        Effect effect,
        PolicyRef policy,
        byte[] paramsHash,
        byte[] sealedPayload,
        byte[] sealedDecision,
        UUID clusterId,
        UUID environmentId,
        Instant requestedAt,
        Instant expiresAt,
        Instant decidedAt,
        Instant runDeadline,
        Instant claimedAt,
        UUID claimedBy,
        Instant finishedAt,
        long requestAuditId,
        int version) {

    String paramsHashHex() {
        return HexFormat.of().formatHex(paramsHash);
    }

    Requester requester() {
        return new Requester(requesterId, requesterUsername, authKind, tokenId);
    }

    OperationScope scope() {
        return new OperationScope(clusterId, environmentId);
    }

    /** The link to the request's page in the console. */
    String link() {
        return "/approvals/" + id;
    }

    HeldOperationView view() {
        return new HeldOperationView(
                id,
                type,
                typeVersion,
                state,
                mode,
                traits,
                scope(),
                summary,
                display,
                params,
                paramsHashHex(),
                effect,
                policy,
                requester(),
                reason,
                approverId,
                approverUsername,
                decisionReason,
                outcomeDetail,
                requestedAt,
                expiresAt,
                decidedAt,
                finishedAt);
    }
}
