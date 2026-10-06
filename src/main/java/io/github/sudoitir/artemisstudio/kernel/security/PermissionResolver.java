package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.plugin.CatalogueEntry;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionScope;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import io.github.sudoitir.artemisstudio.kernel.plugin.ResourceKind;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The {@code @perm} bean used from {@code @PreAuthorize}/{@code @PostFilter} SpEL, and the one decision
 * point for every permission check (authorization spec, ADR-0038).
 *
 * <p>What a permission needs depends on the scope its module declares for it in the catalogue
 * ({@link PermissionScope}): a {@code GLOBAL} permission takes effect only through a global grant, a
 * {@code CLUSTER} one through a global, environment or cluster grant, and a {@code RESOURCE} one through
 * those, or, for a named queue or address, through a team role in the team that owns the name or a share
 * that covers it. Grants only add. A permission that is not in the catalogue, because its module or plugin
 * is disabled or gone, grants nothing, wildcards included.
 *
 * <p>A signed-in user's grants and teams are read from their current {@link AccessSnapshot}, so a change
 * applies on their next request. A principal that carries its own grants (an API key, which acts within
 * grants narrowed when it was authenticated) is checked against those, and gets no team access.
 */
@Component("perm")
@PluginApi
@RequiredArgsConstructor
public class PermissionResolver {

    private final ScopeHierarchy environments;
    private final AccessLoader access;
    private final TeamIndex teams;
    private final FeatureRegistry features;

    /** Global-scope check, for operations with no cluster (settings, user admin, environment CRUD). */
    public boolean can(String permission) {
        return can(null, permission);
    }

    /**
     * Cluster-scoped check: global, or the cluster's environment, or the cluster itself. For a
     * {@code RESOURCE} permission this asks about the whole cluster and is satisfied by grants only,
     * never by a team: to ask about a queue or address, name it with {@link #can(UUID, ResourceRef, String)}.
     */
    public boolean can(UUID clusterId, String permission) {
        return can(currentPrincipal(), clusterId, permission);
    }

    /** The same check for a given principal rather than the current one. */
    public boolean can(StudioPrincipal principal, UUID clusterId, String permission) {
        if (principal == null) {
            return false;
        }
        CatalogueEntry entry = features.permission(permission).orElse(null);
        return entry != null && grantsAllow(principal, entry, clusterId, permission);
    }

    /**
     * Whether the current principal holds {@code action} on one queue or address of the cluster: through
     * a grant that reaches the cluster, a team role in the team that owns the name, or a share that
     * covers it.
     */
    public boolean can(UUID clusterId, ResourceRef resource, String action) {
        return can(currentPrincipal(), clusterId, resource, action);
    }

    /** The same check for a given principal rather than the current one. */
    public boolean can(StudioPrincipal principal, UUID clusterId, ResourceRef resource, String action) {
        if (principal == null) {
            return false;
        }
        CatalogueEntry entry = features.permission(action).orElse(null);
        if (entry == null) {
            return false;
        }
        if (grantsAllow(principal, entry, clusterId, action)) {
            return true;
        }
        if (!teamsApply(principal, entry, clusterId) || !entry.resourceKinds().contains(resource.kind())) {
            return false;
        }
        AccessSnapshot snapshot = access.of(principal.userId());
        boolean owner = teams.ownerOf(clusterId, resource)
                .map(team -> snapshot.holdsInTeam(team, action))
                .orElse(false);
        return owner
                || teams.sharesCovering(clusterId, resource).stream()
                        .anyMatch(s -> snapshot.teamPermissions().containsKey(s.targetTeamId())
                                && Grant.covers(s.permissions(), action));
    }

    /**
     * Whether the current principal may hold {@code action} on some queue or address of the cluster:
     * through a grant that reaches it, or a team or share that has names there. It answers "should this
     * page or control be offered", and must never authorise a change: that is {@link #can(UUID, ResourceRef, String)}.
     */
    public boolean canAnywhere(UUID clusterId, String action) {
        StudioPrincipal principal = currentPrincipal();
        if (principal == null) {
            return false;
        }
        CatalogueEntry entry = features.permission(action).orElse(null);
        if (entry == null) {
            return false;
        }
        if (grantsAllow(principal, entry, clusterId, action)) {
            return true;
        }
        if (!teamsApply(principal, entry, clusterId)) {
            return false;
        }
        AccessSnapshot snapshot = access.of(principal.userId());
        Set<UUID> member = snapshot.teamPermissions().keySet();
        return teams.ownedOn(clusterId).stream()
                        .anyMatch(o -> intersects(o.kinds(), entry.resourceKinds())
                                && snapshot.holdsInTeam(o.teamId(), action))
                || teams.sharedOn(clusterId).stream()
                        .anyMatch(s -> intersects(s.kinds(), entry.resourceKinds())
                                && member.contains(s.targetTeamId())
                                && Grant.covers(s.permissions(), action));
    }

    /**
     * Whether the current principal may see the cluster at all: they may read it, or may read some queue
     * or address on it. Seeing a cluster through a team grants nothing cluster-wide.
     */
    public boolean canSeeCluster(UUID clusterId) {
        return can(clusterId, Permissions.CLUSTER_READ)
                || canAnywhere(clusterId, Permissions.QUEUE_READ)
                || canAnywhere(clusterId, Permissions.ADDRESS_READ);
    }

    /** The grants a principal acts on right now: its own when it carries them, else the user's current ones. */
    public Set<Grant> grantsOf(StudioPrincipal principal) {
        return principal.pinned()
                ? principal.pinnedGrants()
                : access.of(principal.userId()).grants();
    }

    private boolean grantsAllow(StudioPrincipal principal, CatalogueEntry entry, UUID clusterId, String permission) {
        UUID environmentId = clusterId == null || entry.scope() == PermissionScope.GLOBAL
                ? null
                : environments.environmentOf(clusterId);
        for (Grant grant : grantsOf(principal)) {
            boolean scopeMatches =
                    switch (grant.scopeType()) {
                        case GLOBAL -> true;
                        case ENVIRONMENT -> environmentId != null && environmentId.equals(grant.scopeId());
                        case CLUSTER ->
                            entry.scope() != PermissionScope.GLOBAL
                                    && clusterId != null
                                    && clusterId.equals(grant.scopeId());
                    };
            if (scopeMatches && grant.grants(permission)) {
                return true;
            }
        }
        return false;
    }

    private static boolean teamsApply(StudioPrincipal principal, CatalogueEntry entry, UUID clusterId) {
        return clusterId != null && entry.scope() == PermissionScope.RESOURCE && !principal.pinned();
    }

    private static boolean intersects(Set<ResourceKind> a, Set<ResourceKind> b) {
        return !Collections.disjoint(a, b);
    }

    private static StudioPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof StudioPrincipal p) {
            return p;
        }
        return null;
    }
}
