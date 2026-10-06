package io.github.sudoitir.artemisstudio.feature.plugins.work;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import io.github.sudoitir.artemisstudio.kernel.security.ResourceRef;
import java.util.UUID;

/**
 * One permission a unit of work needs its owner to hold, and where.
 *
 * @param clusterId the cluster, or {@code null} for a permission checked without one
 * @param resource the queue or address on that cluster, or {@code null} for the cluster as a whole
 * @param permission the permission action, such as {@code message:send}
 */
@PluginApi
public record WorkNeed(UUID clusterId, ResourceRef resource, String permission) {

    public WorkNeed {
        if (permission == null || permission.isBlank()) {
            throw new IllegalArgumentException("A need names a permission.");
        }
        if (resource != null && clusterId == null) {
            throw new IllegalArgumentException("A queue or address belongs to a cluster: name the cluster too.");
        }
    }

    /** A permission held on the whole cluster. */
    public static WorkNeed onCluster(UUID clusterId, String permission) {
        return new WorkNeed(clusterId, null, permission);
    }

    /** A permission held on one queue or address of the cluster. */
    public static WorkNeed on(UUID clusterId, ResourceRef resource, String permission) {
        return new WorkNeed(clusterId, resource, permission);
    }

    /** A permission checked without a cluster. */
    public static WorkNeed global(String permission) {
        return new WorkNeed(null, null, permission);
    }
}
