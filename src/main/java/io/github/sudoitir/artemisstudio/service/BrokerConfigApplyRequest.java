package io.github.sudoitir.artemisstudio.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What an operator asked an apply to do beyond "the current revision, every live
 * node".
 *
 * @param revision the revision number to apply; null means the current one, and a
 *     number that is not current is refused
 * @param nodeIds live nodes to target; empty means all
 * @param canaryNodeId the node to apply first
 * @param removeUndeclared opt in to removing settings and diverts Studio did not apply
 * @param acknowledgedHazards the High hazard identifiers the operator acknowledged
 * @param expectedPlanHash the plan the operator previewed; a real run refuses when it changed
 * @param override lift the step cap for this run
 */
public record BrokerConfigApplyRequest(
        Integer revision,
        Set<UUID> nodeIds,
        UUID canaryNodeId,
        boolean removeUndeclared,
        List<String> acknowledgedHazards,
        String expectedPlanHash,
        boolean override) {

    public BrokerConfigApplyRequest {
        nodeIds = nodeIds == null ? Set.of() : Set.copyOf(nodeIds);
        acknowledgedHazards = acknowledgedHazards == null ? List.of() : List.copyOf(acknowledgedHazards);
    }

    public static BrokerConfigApplyRequest everything() {
        return new BrokerConfigApplyRequest(null, Set.of(), null, false, List.of(), null, false);
    }
}
