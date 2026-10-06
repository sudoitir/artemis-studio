package io.github.sudoitir.artemisstudio.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.PrincipalType;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.RoleRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.RoleView;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

/** What a role may hold: every permission its permissions require, and for a team role only resource ones. */
@ExtendWith(AdminAuthenticationExtension.class)
class RoleServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    RoleService roleService;

    @Autowired
    TeamService teams;

    @Autowired
    io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository users;

    private static String name() {
        return "role-" + UUID.randomUUID();
    }

    private RoleView create(boolean teamAssignable, String... permissions) {
        return roleService.create(new RoleRequest(name(), List.of(permissions), false, teamAssignable));
    }

    private static void rejected(Runnable save, String slug, String... mentions) {
        assertThatThrownBy(save::run).isInstanceOfSatisfying(ConflictException.class, e -> {
            assertThat(e.slug()).isEqualTo(slug);
            for (String mention : mentions) {
                assertThat(e.getMessage()).contains(mention);
            }
        });
    }

    @Test
    void aRoleMissingAPermissionTheOnesItHoldsRequireIsRefusedNamingWhatIsMissing() {
        rejected(
                () -> create(false, "queue:purge", "message:send"),
                "role-missing-requirements",
                "queue:purge needs queue:read",
                "message:send needs address:read");
    }

    @Test
    void aRoleHoldingWhatItsPermissionsRequireIsSaved() {
        RoleView role = create(false, "queue:purge", "queue:read");

        assertThat(role.permissions()).containsExactlyInAnyOrder("queue:purge", "queue:read");
        assertThat(role.teamAssignable()).isFalse();
    }

    @Test
    void aWildcardSatisfiesTheRequirementsItCoversAndIsCheckedForWhatItStandsFor() {
        create(false, "*");
        create(false, "queue:*", "message:read");

        rejected(() -> create(false, "message:*"), "role-missing-requirements", "needs queue:read");
    }

    @Test
    void aRoleUpdateIsCheckedTheSameWay() {
        RoleView role = create(false, "queue:purge", "queue:read");

        rejected(
                () -> roleService.update(role.id(), new RoleRequest(role.name(), List.of("queue:purge"), false, false)),
                "role-missing-requirements",
                "queue:purge needs queue:read");
    }

    @Test
    void aTeamRoleHoldsOnlyResourcePermissionsAndTeamAdmin() {
        RoleView role = create(true, "queue:read", "queue:purge", "team:admin");
        assertThat(role.teamAssignable()).isTrue();

        rejected(() -> create(true, "queue:read", "cluster:read"), "team-role-permissions", "cluster:read");
        rejected(() -> create(true, "queue:read", "user:admin"), "team-role-permissions", "user:admin");
        rejected(() -> create(true, "*"), "team-role-permissions", "*");
        rejected(() -> create(true, "not:catalogued"), "team-role-permissions", "not:catalogued");
    }

    @Test
    void aRoleCannotBecomeATeamRoleWhileItHoldsOtherPermissions() {
        RoleView role = create(false, "cluster:read", "queue:read");

        rejected(
                () -> roleService.update(role.id(), new RoleRequest(role.name(), role.permissions(), false, true)),
                "team-role-permissions",
                "cluster:read");
    }

    @Test
    void aBuiltInRoleKeepsItsUseAsATeamRole() {
        RoleView builtin = roleService.list().stream()
                .filter(r -> r.name().equals("TEAM_VIEWER"))
                .findFirst()
                .orElseThrow();
        RoleView viewer = roleService.list().stream()
                .filter(r -> r.name().equals("VIEWER"))
                .findFirst()
                .orElseThrow();

        assertThat(builtin.teamAssignable()).isTrue();
        assertThat(viewer.teamAssignable()).isFalse();
        rejected(
                () -> roleService.update(
                        builtin.id(), new RoleRequest(builtin.name(), builtin.permissions(), false, false)),
                "builtin-role");
    }

    @Test
    void aRoleThatIsATeamMembersRoleCannotBeDeletedOrStopBeingATeamRole() {
        RoleView role = create(true, "queue:read");
        UUID team = teams.create("roles-" + UUID.randomUUID()).id();
        String username = "role-member-" + UUID.randomUUID();
        var user = io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity.local(
                username, null, "{noop}x");
        user.setMustChangePassword(false);
        UUID userId = users.save(user).getId();
        teams.addMember(team, new MemberRequest(PrincipalType.USER, userId, null, null, role.id()));

        rejected(() -> roleService.delete(role.id()), "role-in-use");
        rejected(
                () -> roleService.update(role.id(), new RoleRequest(role.name(), role.permissions(), false, false)),
                "role-in-use");

        SecurityContextHolder.clearContext();
        new AdminAuthenticationExtension().beforeEach(null);
        teams.delete(team);
        roleService.delete(role.id());
    }
}
