package io.github.sudoitir.artemisstudio.security;

import java.util.Set;
import java.util.UUID;

/** One resolved role grant: a set of permissions held at a scope (ADR-0038). */
public record Grant(ScopeType scopeType, UUID scopeId, Set<String> permissions) {

    public enum ScopeType {
        GLOBAL,
        ENVIRONMENT,
        CLUSTER
    }

    /** True if this grant's permission set contains, or wildcard-covers, the requested permission. */
    public boolean grants(String permission) {
        if (permissions.contains(Permissions.WILDCARD) || permissions.contains(permission)) {
            return true;
        }
        int colon = permission.indexOf(':');
        if (colon < 0) {
            return false;
        }
        return permissions.contains(permission.substring(0, colon) + ":*");
    }
}
