package io.github.sudoitir.artemisstudio.kernel.security;

/**
 * The permission strings the security kernel itself checks (ADR-0038). Every other
 * permission is declared by the module that checks it, and the catalogue a role is
 * built from is assembled from the enabled modules' descriptors. A role may still
 * legally hold any string: {@code role_permission.action} is free-form data.
 */
public final class Permissions {

    public static final String WILDCARD = "*";

    /** Reading a cluster at all — the permission every cluster-scoped read checks first. */
    public static final String CLUSTER_READ = "cluster:read";

    public static final String USER_ADMIN = "user:admin";

    private Permissions() {}
}
