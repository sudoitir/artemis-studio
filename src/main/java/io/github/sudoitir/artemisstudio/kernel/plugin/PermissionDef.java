package io.github.sudoitir.artemisstudio.kernel.plugin;

import java.util.Set;

/**
 * One permission string a module checks (ADR-0038), with its role-editor label, the scope it takes
 * effect at and the permissions a role must hold with it.
 *
 * @param resourceKinds the kinds of resource a {@link PermissionScope#RESOURCE} permission acts on;
 *     empty for every other scope
 * @param requires permissions that must be held wherever this one is, from this catalogue or another
 *     module's
 */
public record PermissionDef(
        String action, String label, PermissionScope scope, Set<ResourceKind> resourceKinds, Set<String> requires) {

    public PermissionDef {
        if (scope == PermissionScope.RESOURCE && resourceKinds.isEmpty()) {
            throw new IllegalArgumentException("Permission '" + action + "' acts on a resource but names no kind");
        }
        if (scope != PermissionScope.RESOURCE && !resourceKinds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Permission '" + action + "' names resource kinds but is not 'resource'");
        }
        resourceKinds = Set.copyOf(resourceKinds);
        requires = Set.copyOf(requires);
    }

    public static PermissionDef global(String action, String label) {
        return new PermissionDef(action, label, PermissionScope.GLOBAL, Set.of(), Set.of());
    }

    public static PermissionDef cluster(String action, String label, String... requires) {
        return new PermissionDef(action, label, PermissionScope.CLUSTER, Set.of(), Set.of(requires));
    }

    public static PermissionDef resource(String action, String label, ResourceKind kind, String... requires) {
        return new PermissionDef(action, label, PermissionScope.RESOURCE, Set.of(kind), Set.of(requires));
    }
}
