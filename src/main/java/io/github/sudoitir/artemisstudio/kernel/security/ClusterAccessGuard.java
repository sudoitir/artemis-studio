package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceForbiddenException;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import io.github.sudoitir.artemisstudio.kernel.plugin.ResourceKind;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Guards a per-cluster operation. A caller with no grant on the target cluster
 * gets a {@link NotFoundException} (404), not a permission-denied response —
 * revealing whether a cluster id exists to someone with no grant on it would
 * leak information the authorization spec says must stay hidden. Used instead
 * of {@code @PreAuthorize} on any method addressed by a specific cluster id;
 * {@code @PreAuthorize} stays for operations with no cluster to hide (global
 * writes, settings, user/role/environment administration).
 *
 * <p>An operation on a queue or address is guarded against that resource instead
 * ({@link #requireResource}): a caller who may not read it gets the not-found a missing one
 * gets, and one who may read it and may not do this gets a 403 naming the permission.
 */
@Component
@PluginApi
@RequiredArgsConstructor
public class ClusterAccessGuard {

    /** One permission needed on one queue or address, for an operation that touches several. */
    public record Requirement(ResourceRef resource, String permission) {}

    private final PermissionResolver perm;

    public void requireCluster(UUID clusterId, String permission) {
        if (!perm.can(clusterId, permission)) {
            throw new NotFoundException("cluster", clusterId);
        }
    }

    /**
     * Gate for listing resources of a cluster: the caller may read the cluster, or some queue or address on
     * it. What they then see is filtered by {@link PermissionResolver#filter}; this only keeps a caller with
     * nothing on the cluster from learning that it exists.
     */
    public void requireVisible(UUID clusterId) {
        if (!perm.canSeeCluster(clusterId)) {
            throw new NotFoundException("cluster", clusterId);
        }
    }

    /** {@link #requireAll} for one resource. */
    public void requireResource(UUID clusterId, ResourceRef resource, String permission) {
        requireAll(clusterId, List.of(new Requirement(resource, permission)));
    }

    /**
     * Whether the caller holds the permission on the cluster as a whole, through a grant. For an operation
     * that checks each resource it touches only when this is false.
     */
    public boolean holds(UUID clusterId, String permission) {
        return perm.can(clusterId, permission);
    }

    /** Whether {@link #requireAll} would pass, for filtering a list rather than refusing a request. */
    public boolean mayAll(UUID clusterId, List<Requirement> requirements) {
        return requirements.stream()
                .allMatch(r -> perm.can(clusterId, r.resource(), r.resource().readPermission())
                        && perm.can(clusterId, r.resource(), r.permission()));
    }

    /**
     * Checks every requirement before anything is done. A resource the caller may not read is not found,
     * whatever else is asked of it, and is reported before any refusal, so a composite operation never
     * tells a caller that a target they cannot see would have been allowed. Only then is each permission
     * checked, and the first one missing is a 403 naming it and the resource. When several resources are
     * involved the not-found names none of them, so it cannot say which one the caller may not see.
     */
    public void requireAll(UUID clusterId, List<Requirement> requirements) {
        for (Requirement r : requirements) {
            if (!perm.can(clusterId, r.resource(), r.resource().readPermission())) {
                throw requirements.size() == 1
                        ? unreadable(clusterId, r.resource())
                        : unreadableAmongSeveral(clusterId);
            }
        }
        for (Requirement r : requirements) {
            if (!perm.can(clusterId, r.resource(), r.permission())) {
                throw new ResourceForbiddenException(
                        r.permission(), kindOf(r.resource()), r.resource().name());
            }
        }
    }

    /**
     * Creating a queue or address that does not exist yet: allowed only where a grant or the caller's team
     * patterns cover the name. A caller who sees the cluster is told where they may create; one who does not
     * is told the cluster does not exist.
     */
    public void requireCreate(UUID clusterId, ResourceRef resource, String permission) {
        if (perm.can(clusterId, resource, permission)) {
            return;
        }
        if (!perm.canSeeCluster(clusterId)) {
            throw new NotFoundException("cluster", clusterId);
        }
        List<String> patterns = perm.patternsHolding(clusterId, resource.kind(), permission);
        String where = patterns.isEmpty()
                ? ""
                : " You may create " + kindOf(resource) + "s under: " + String.join(", ", patterns) + ".";
        throw new ResourceForbiddenException(
                permission,
                kindOf(resource),
                resource.name(),
                "You do not hold " + permission + " on " + kindOf(resource) + " " + resource.name() + "." + where);
    }

    /**
     * An operation on every queue or address a pattern can match, such as a capture or a query: allowed
     * when a grant reaches the cluster, or when one of the caller's team patterns or shares that carries
     * the permission covers the whole pattern. Otherwise a caller who sees the cluster is refused and told
     * to narrow the pattern; one who does not is told the cluster does not exist.
     */
    public void requireOnAll(UUID clusterId, ResourceKind kind, String pattern, String permission) {
        if (perm.canOnAll(clusterId, kind, pattern, permission)) {
            return;
        }
        if (!perm.canSeeCluster(clusterId)) {
            throw new NotFoundException("cluster", clusterId);
        }
        String noun = kind.name().toLowerCase(Locale.ROOT);
        throw new ResourceForbiddenException(
                permission,
                noun,
                pattern,
                "You do not hold " + permission + " on every " + noun + " that '" + pattern
                        + "' can match. Narrow it to names your teams own.");
    }

    /** Whether {@link #requireOnAll} would pass, for filtering a list rather than refusing a request. */
    public boolean mayOnAll(UUID clusterId, ResourceKind kind, String pattern, String permission) {
        return perm.canOnAll(clusterId, kind, pattern, permission);
    }

    private NotFoundException unreadable(UUID clusterId, ResourceRef resource) {
        return perm.canSeeCluster(clusterId)
                ? new NotFoundException(kindOf(resource), resource.name())
                : new NotFoundException("cluster", clusterId);
    }

    private NotFoundException unreadableAmongSeveral(UUID clusterId) {
        return perm.canSeeCluster(clusterId)
                ? new NotFoundException("A queue or address named in the request does not exist.")
                : new NotFoundException("cluster", clusterId);
    }

    private static String kindOf(ResourceRef resource) {
        return resource.kind().name().toLowerCase(Locale.ROOT);
    }
}
