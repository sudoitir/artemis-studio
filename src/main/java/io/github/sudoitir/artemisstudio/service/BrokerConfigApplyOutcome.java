package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigApplyEntity.Outcome;
import java.util.List;
import java.util.UUID;

/**
 * What a configuration apply — dry or real — produced (ADR-0067 D4): the plan that
 * was computed, and per node, per step, what happened. The same shape serves the
 * preview and the result so the two are comparable.
 */
public record BrokerConfigApplyOutcome(
        Long applyId,
        boolean dryRun,
        Outcome outcome,
        int revision,
        Plan plan,
        List<NodeApply> nodes,
        int stepCap,
        boolean overCap,
        String summary,
        Long auditEventId) {

    public enum StepStatus {
        WOULD_APPLY,
        APPLIED,
        ALREADY,
        FAILED,
        NOT_ATTEMPTED,
        SKIPPED_NOT_LIVE
    }

    public enum Verification {
        NOT_VERIFIED,
        VERIFIED,
        UNVERIFIABLE,
        MISMATCH
    }

    public record NodeApply(
            UUID nodeId,
            String nodeName,
            boolean live,
            boolean canary,
            String unavailableReason,
            List<StepApply> steps,
            String note) {

        public boolean anyFailed() {
            return steps.stream()
                    .anyMatch(s -> s.status() == StepStatus.FAILED || s.verified() == Verification.MISMATCH);
        }
    }

    public record StepApply(
            String stepId,
            Plan.Section section,
            String key,
            Plan.Op op,
            String description,
            StepStatus status,
            Verification verified,
            String error) {}

    public boolean anyFailed() {
        return nodes.stream().anyMatch(NodeApply::anyFailed);
    }
}
