package io.github.sudoitir.artemisstudio.security;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The {@code @perm} bean used from {@code @PreAuthorize}/{@code @PostFilter}
 * SpEL. Walks a request's target cluster up to its environment and to global
 * scope (ADR-0038), testing every {@link Grant} the current principal holds at
 * a matching scope.
 */
@Component("perm")
@RequiredArgsConstructor
public class PermissionResolver {

    private final ClusterEnvironmentIndex environments;

    /** Global-scope check, for operations with no cluster (settings, user admin, environment CRUD). */
    public boolean can(String permission) {
        return can(null, permission);
    }

    /** Cluster-scoped check: global, or the cluster's environment, or the cluster itself. */
    public boolean can(UUID clusterId, String permission) {
        StudioPrincipal principal = currentPrincipal();
        if (principal == null) {
            return false;
        }
        UUID environmentId = clusterId == null ? null : environments.environmentOf(clusterId);
        for (Grant grant : principal.grants()) {
            boolean scopeMatches =
                    switch (grant.scopeType()) {
                        case GLOBAL -> true;
                        case ENVIRONMENT -> environmentId != null && environmentId.equals(grant.scopeId());
                        case CLUSTER -> clusterId != null && clusterId.equals(grant.scopeId());
                    };
            if (scopeMatches && grant.grants(permission)) {
                return true;
            }
        }
        return false;
    }

    private static StudioPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof StudioPrincipal p) {
            return p;
        }
        return null;
    }
}
