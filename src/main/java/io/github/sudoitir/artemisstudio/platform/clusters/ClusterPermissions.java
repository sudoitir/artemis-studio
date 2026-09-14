package io.github.sudoitir.artemisstudio.platform.clusters;

/** Permission strings this module checks (ADR-0038); declared in its module descriptor. */
public final class ClusterPermissions {

    public static final String CLUSTER_WRITE = "cluster:write";

    public static final String ENVIRONMENT_READ = "environment:read";

    public static final String ENVIRONMENT_WRITE = "environment:write";

    private ClusterPermissions() {}
}
