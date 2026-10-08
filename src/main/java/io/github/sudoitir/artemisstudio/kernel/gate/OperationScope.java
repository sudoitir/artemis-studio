package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.UUID;

/**
 * Where an operation acts. Approvers need the approver permission at this cluster.
 *
 * @param clusterId the cluster, or {@code null} for an installation-wide operation
 * @param environmentId the cluster's environment, or {@code null}
 */
@PluginApi
public record OperationScope(UUID clusterId, UUID environmentId) {

    public static final OperationScope GLOBAL = new OperationScope(null, null);

    public static OperationScope cluster(UUID clusterId, UUID environmentId) {
        return new OperationScope(clusterId, environmentId);
    }
}
