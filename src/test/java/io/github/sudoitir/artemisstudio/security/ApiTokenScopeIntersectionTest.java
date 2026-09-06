package io.github.sudoitir.artemisstudio.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.persist.AppUserEntity;
import io.github.sudoitir.artemisstudio.persist.AppUserRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.persist.RoleEntity;
import io.github.sudoitir.artemisstudio.persist.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.persist.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.persist.RoleRepository;
import io.github.sudoitir.artemisstudio.persist.UserRoleEntity;
import io.github.sudoitir.artemisstudio.persist.UserRoleRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A key's grants are intersected with its owner's live grants (ADR-0039). The
 * question this fixes is which owner grant reaches which token grant.
 *
 * <p>Scope-id equality alone made narrowing impossible for the people who most
 * need it: an administrator's grants are global, so a key scoped to one cluster
 * intersected to nothing and authenticated with no permissions at all. That is
 * the opposite of what minting a narrow key is for, and it is why the API-key
 * dialog's scope picker exists (ADR-0046).
 *
 * <p>The widening runs one way only, which is the half worth guarding: a
 * cluster-scoped owner must never be able to mint a global key.
 */
class ApiTokenScopeIntersectionTest extends PostgresIntegrationTest {

    @Autowired
    ApiTokenService tokens;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    ClusterRepository clusters;

    private UUID owner(Grant.ScopeType scopeType, UUID scopeId, String... permissions) {
        String username = "scope-" + UUID.randomUUID();
        AppUserEntity user = AppUserEntity.local(username, username + "@example.test", "{noop}unused");
        user.setMustChangePassword(false);
        users.save(user);
        RoleEntity role = roles.save(new RoleEntity("role-" + UUID.randomUUID(), false));
        for (String p : permissions) {
            rolePermissions.save(new RolePermissionEntity(role.getId(), p));
        }
        userRoles.save(new UserRoleEntity(
                user.getId(),
                role.getId(),
                scopeType.name(),
                scopeType == Grant.ScopeType.GLOBAL ? ScopeIds.GLOBAL : scopeId));
        return user.getId();
    }

    private StudioPrincipal authenticateKeyFor(UUID userId, Grant tokenGrant) {
        var minted = tokens.mint(userId, "k-" + UUID.randomUUID(), null, List.of(tokenGrant));
        return tokens.authenticate(minted.plaintext());
    }

    @Test
    void aGlobalOwnerCanMintAKeyNarrowedToOneCluster() {
        UUID cluster = clusters.save(new ClusterEntity("narrow-" + UUID.randomUUID(), null, null))
                .getId();
        UUID user = owner(Grant.ScopeType.GLOBAL, null, Permissions.CLUSTER_READ);

        StudioPrincipal principal =
                authenticateKeyFor(user, new Grant(Grant.ScopeType.CLUSTER, cluster, Set.of(Permissions.CLUSTER_READ)));

        assertThat(principal).isNotNull();
        assertThat(principal.grants())
                .describedAs("a global owner narrowing a key to one cluster used to get an empty grant set")
                .containsExactly(new Grant(Grant.ScopeType.CLUSTER, cluster, Set.of(Permissions.CLUSTER_READ)));

        clusters.deleteById(cluster);
    }

    @Test
    void aClusterScopedOwnerCannotMintAGlobalKey() {
        UUID cluster = clusters.save(new ClusterEntity("bounded-" + UUID.randomUUID(), null, null))
                .getId();
        UUID user = owner(Grant.ScopeType.CLUSTER, cluster, Permissions.CLUSTER_READ);

        StudioPrincipal principal = authenticateKeyFor(
                user, new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of(Permissions.CLUSTER_READ)));

        assertThat(principal).isNotNull();
        assertThat(principal.grants())
                .describedAs("widening must not run in this direction — a key cannot exceed its owner")
                .isEmpty();

        clusters.deleteById(cluster);
    }

    @Test
    void aClusterScopedOwnerCannotReachAnotherCluster() {
        UUID mine = clusters.save(new ClusterEntity("mine-" + UUID.randomUUID(), null, null))
                .getId();
        UUID theirs = clusters.save(new ClusterEntity("theirs-" + UUID.randomUUID(), null, null))
                .getId();
        UUID user = owner(Grant.ScopeType.CLUSTER, mine, Permissions.CLUSTER_READ);

        StudioPrincipal principal =
                authenticateKeyFor(user, new Grant(Grant.ScopeType.CLUSTER, theirs, Set.of(Permissions.CLUSTER_READ)));

        assertThat(principal.grants()).isEmpty();

        clusters.deleteById(mine);
        clusters.deleteById(theirs);
    }

    @Test
    void aPermissionTheOwnerLacksIsStillStripped() {
        UUID cluster = clusters.save(new ClusterEntity("strip-" + UUID.randomUUID(), null, null))
                .getId();
        UUID user = owner(Grant.ScopeType.GLOBAL, null, Permissions.CLUSTER_READ);

        StudioPrincipal principal =
                authenticateKeyFor(user, new Grant(Grant.ScopeType.CLUSTER, cluster, Set.of(Permissions.QUEUE_PURGE)));

        assertThat(principal.grants())
                .describedAs("the scope walk widens the scope, never the permission set")
                .isEmpty();

        clusters.deleteById(cluster);
    }
}
