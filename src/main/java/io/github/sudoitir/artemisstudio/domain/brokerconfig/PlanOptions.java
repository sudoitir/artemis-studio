package io.github.sudoitir.artemisstudio.domain.brokerconfig;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What the operator asked the plan to consider beyond the declaration itself.
 *
 * @param nodeIds live nodes to target; empty means every live node
 * @param canaryNodeId the node to apply first; null means the first targeted node by name
 * @param removeUndeclared opt in to removing settings and diverts Studio did not apply (D6)
 * @param reportUndeclared list resources that exist and are not declared (drift only)
 * @param undeclaredExclusions match patterns whose resources are not reported as undeclared
 */
public record PlanOptions(
        Set<UUID> nodeIds,
        UUID canaryNodeId,
        boolean removeUndeclared,
        boolean reportUndeclared,
        List<String> undeclaredExclusions) {

    public PlanOptions {
        nodeIds = nodeIds == null ? Set.of() : Set.copyOf(nodeIds);
        undeclaredExclusions = undeclaredExclusions == null ? List.of() : List.copyOf(undeclaredExclusions);
    }

    public static PlanOptions defaults() {
        return new PlanOptions(Set.of(), null, false, false, List.of());
    }

    public static PlanOptions drift(boolean reportUndeclared, List<String> exclusions) {
        return new PlanOptions(Set.of(), null, false, reportUndeclared, exclusions);
    }
}
