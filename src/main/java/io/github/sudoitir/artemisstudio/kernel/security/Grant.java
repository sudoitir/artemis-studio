package io.github.sudoitir.artemisstudio.kernel.security;

import java.io.Serializable;
import java.util.Set;
import java.util.UUID;

/**
 * One resolved role grant: a set of permissions held at a scope (ADR-0038). Serializable because a
 * signed-in principal carries its grants in the JDBC session store (ADR-0037).
 */
public record Grant(ScopeType scopeType, UUID scopeId, Set<String> permissions) implements Serializable {

    public enum ScopeType {
        GLOBAL,
        ENVIRONMENT,
        CLUSTER
    }

    /** True if this grant's permission set contains, or wildcard-covers, the requested permission. */
    public boolean grants(String permission) {
        return covers(permissions, permission);
    }

    /** True if {@code held} contains, or wildcard-covers, the requested permission. */
    public static boolean covers(Set<String> held, String permission) {
        if (held.contains(Permissions.WILDCARD) || held.contains(permission)) {
            return true;
        }
        int colon = permission.indexOf(':');
        return colon >= 0 && held.contains(permission.substring(0, colon) + ":*");
    }
}
