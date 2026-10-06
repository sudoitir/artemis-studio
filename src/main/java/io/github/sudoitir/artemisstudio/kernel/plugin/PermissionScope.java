package io.github.sudoitir.artemisstudio.kernel.plugin;

/** Where a permission takes effect (authorization spec): the widest grant it needs and the narrowest it can be given at. */
public enum PermissionScope {

    /** Checked without a cluster: only a global grant makes it take effect. */
    GLOBAL,

    /** Checked against a cluster: a global, environment or cluster grant makes it take effect. */
    CLUSTER,

    /** Checked against a queue or address of a cluster: a platform grant, a team role or a share makes it take effect. */
    RESOURCE
}
