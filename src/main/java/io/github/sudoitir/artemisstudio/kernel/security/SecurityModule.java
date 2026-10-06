package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;

/** Users, roles, grants, the scope walk and session lifetimes. Module descriptor (ADR-0070). */
public final class SecurityModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("security")
            .title("Security")
            .kind(FeatureDescriptor.Kind.KERNEL)
            .required(true)
            .permission(PermissionDef.cluster(Permissions.CLUSTER_READ, "View clusters and topology"))
            .permission(PermissionDef.global(Permissions.USER_ADMIN, "Manage users, roles, and grants"))
            .permission(
                    PermissionDef.global(Permissions.TEAM_ADMIN, "Manage teams, their patterns, members and shares"))
            .settingKey(SessionLifetimes.IDLE_TIMEOUT)
            .settingKey(SessionLifetimes.ABSOLUTE_LIFETIME)
            .build();

    private SecurityModule() {}
}
