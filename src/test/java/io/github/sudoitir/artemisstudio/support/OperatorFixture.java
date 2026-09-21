package io.github.sudoitir.artemisstudio.support;

import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.GrantLoader;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A real operator account holding every permission globally, signed in on the calling thread the
 * way a session would be. For tests whose subject re-reads the account's grants, where the
 * account-less principal of {@link AdminAuthenticationExtension} has nothing to re-read.
 */
public final class OperatorFixture {

    private OperatorFixture() {}

    /** Create the account, sign it in on this thread, and return its id. */
    public static UUID signIn(
            AppUserRepository users,
            RoleRepository roles,
            RolePermissionRepository rolePermissions,
            UserRoleRepository userRoles,
            GrantLoader grants) {
        return signIn(
                users,
                roles,
                rolePermissions,
                userRoles,
                grants,
                Grant.ScopeType.GLOBAL,
                ScopeIds.GLOBAL,
                Permissions.WILDCARD);
    }

    /** As {@link #signIn}, holding only {@code permissions}, granted on one cluster. */
    public static UUID signInOnCluster(
            AppUserRepository users,
            RoleRepository roles,
            RolePermissionRepository rolePermissions,
            UserRoleRepository userRoles,
            GrantLoader grants,
            UUID clusterId,
            String... permissions) {
        return signIn(
                users, roles, rolePermissions, userRoles, grants, Grant.ScopeType.CLUSTER, clusterId, permissions);
    }

    private static UUID signIn(
            AppUserRepository users,
            RoleRepository roles,
            RolePermissionRepository rolePermissions,
            UserRoleRepository userRoles,
            GrantLoader grants,
            Grant.ScopeType scope,
            UUID scopeId,
            String... permissions) {
        String username = "operator-" + UUID.randomUUID();
        AppUserEntity user = AppUserEntity.local(username, username + "@example.test", "{noop}unused");
        user.setMustChangePassword(false);
        users.save(user);
        RoleEntity role = roles.save(new RoleEntity("role-" + UUID.randomUUID(), false));
        for (String permission : permissions) {
            rolePermissions.save(new RolePermissionEntity(role.getId(), permission));
        }
        userRoles.save(new UserRoleEntity(user.getId(), role.getId(), scope.name(), scopeId));
        StudioPrincipal principal = new StudioPrincipal(user.getId(), username, grants.loadFor(user.getId()), false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        return user.getId();
    }

    /** Withdraw every role the account holds, as an administrator would. */
    public static void revokeAll(UserRoleRepository userRoles, UUID userId) {
        userRoles.deleteAll(userRoles.findByIdUserId(userId));
    }
}
