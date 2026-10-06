package io.github.sudoitir.artemisstudio.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.security.AccessChanges;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.PatternKind;
import io.github.sudoitir.artemisstudio.kernel.security.ResourceNames;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.web.AccessViews.AccessSummary;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.PatternRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.PrincipalType;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.EnvironmentEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.EnvironmentRepository;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The caller's own access summary (authorization spec): what the console reads to offer or withhold controls. It
 * must agree with the resolver that enforces, in particular for environment-scope grants and for a user who holds
 * rights only through a team.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class MyAccessIntegrationTest extends PostgresIntegrationTest {

    @MockitoBean
    ResourceNames names;

    @Autowired
    MyAccess myAccess;

    @Autowired
    TeamService teams;

    @Autowired
    RoleService roleService;

    @Autowired
    RoleRepository roles;

    @Autowired
    AppUserRepository users;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    EnvironmentRepository environments;

    @Autowired
    ClusterEnvironmentIndex environmentIndex;

    @Autowired
    AccessChanges accessChanges;

    private UUID user() {
        AppUserEntity user = AppUserEntity.local("summary-" + UUID.randomUUID(), null, "{noop}x");
        user.setMustChangePassword(false);
        return users.save(user).getId();
    }

    private UUID role(String name) {
        return roles.findByName(name).orElseThrow().getId();
    }

    private void asUser(UUID userId) {
        StudioPrincipal principal = StudioPrincipal.live(userId, "member", false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private UUID cluster(UUID environmentId) {
        UUID id = clusters.save(new ClusterEntity("summary-" + UUID.randomUUID(), null, environmentId))
                .getId();
        environmentIndex.invalidate();
        return id;
    }

    private UUID team(String name) {
        return teams.create(name + "-" + UUID.randomUUID()).id();
    }

    @Test
    void aTeamOnlyAdminIsListedAsOneAndHoldsNothingGlobally() {
        UUID orders = team("orders");
        UUID boss = user();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, boss, null, null, role("TEAM_ADMIN")));
        UUID viewer = user();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, viewer, null, null, role("TEAM_VIEWER")));

        asUser(boss);
        AccessSummary asAdmin = myAccess.of(null);
        asUser(viewer);
        AccessSummary asViewer = myAccess.of(null);

        assertThat(asAdmin.permissions()).doesNotContain("team:admin", "user:admin");
        assertThat(asAdmin.canSeeCluster()).isNull();
        assertThat(asAdmin.teams()).singleElement().satisfies(t -> {
            assertThat(t.teamId()).isEqualTo(orders);
            assertThat(t.roleName()).isEqualTo("TEAM_ADMIN");
            assertThat(t.teamAdmin()).isTrue();
        });
        assertThat(asViewer.teams())
                .singleElement()
                .satisfies(t -> assertThat(t.teamAdmin()).isFalse());
    }

    @Test
    void anEnvironmentGrantCoversTheClustersOfThatEnvironmentOnly() {
        UUID live = environments
                .save(new EnvironmentEntity("live-" + UUID.randomUUID(), null, 1))
                .getId();
        UUID inLive = cluster(live);
        UUID elsewhere = cluster(null);
        var reader = roleService.create(new io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.RoleRequest(
                "env-reader-" + UUID.randomUUID(), java.util.List.of("queue:read", "cluster:read"), false, false));
        UUID someone = user();
        userRoles.save(new UserRoleEntity(someone, reader.id(), Grant.ScopeType.ENVIRONMENT.name(), live));
        accessChanges.changedFor(someone);

        asUser(someone);

        assertThat(myAccess.of(inLive).permissions()).contains("queue:read", "cluster:read");
        assertThat(myAccess.of(inLive).canSeeCluster()).isTrue();
        assertThat(myAccess.of(elsewhere).permissions()).isEmpty();
        assertThat(myAccess.of(elsewhere).canSeeCluster()).isFalse();
        assertThat(myAccess.of(null).permissions()).isEmpty();
    }

    @Test
    void ATeamMemberHoldsResourcePermissionsAnywhereButNothingClusterWide() {
        UUID prod = cluster(null);
        UUID staging = cluster(null);
        UUID orders = team("orders");
        teams.addPattern(orders, new PatternRequest(prod, PatternKind.QUEUE, "orders.#"));
        UUID operator = user();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, operator, null, null, role("TEAM_OPERATOR")));

        asUser(operator);
        AccessSummary onProd = myAccess.of(prod);
        AccessSummary onStaging = myAccess.of(staging);

        assertThat(onProd.permissions()).doesNotContain("queue:read", "queue:purge", "cluster:read");
        assertThat(onProd.anywhere()).contains("queue:read", "queue:purge");
        assertThat(onProd.canSeeCluster()).isTrue();
        assertThat(onStaging.anywhere()).isEmpty();
        assertThat(onStaging.canSeeCluster()).isFalse();
    }

    @Test
    void anApiKeyHasNoTeams() {
        UUID orders = team("orders");
        UUID owner = user();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, owner, null, null, role("TEAM_ADMIN")));
        StudioPrincipal key = new StudioPrincipal(
                owner, "key", Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of("cluster:read"))), false, "ci-key");
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(key, null, key.getAuthorities()));

        AccessSummary summary = myAccess.of(null);

        assertThat(summary.teams()).isEmpty();
        assertThat(summary.permissions()).containsExactly("cluster:read");
    }
}
