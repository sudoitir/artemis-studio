package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import io.github.sudoitir.artemisstudio.kernel.plugin.IdentityProviderListing;
import io.github.sudoitir.artemisstudio.kernel.security.internal.GroupMappingService;
import io.github.sudoitir.artemisstudio.kernel.security.internal.IdentityProviderCatalog;
import io.github.sudoitir.artemisstudio.kernel.security.internal.RoleService;
import io.github.sudoitir.artemisstudio.kernel.security.internal.TeamService;
import io.github.sudoitir.artemisstudio.kernel.security.internal.UserService;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.web.GroupMappingViews.GroupMappingRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.PrincipalType;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.GrantRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.RoleRequest;
import io.github.sudoitir.artemisstudio.support.OperatorFixture;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Every change to what anyone may do leaves a row naming who made it and whose access it touched (ADR-0181). */
class AccessChangeLogIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    GrantLoader grants;

    @Autowired
    UserService userService;

    @Autowired
    RoleService roleService;

    @Autowired
    TeamService teamService;

    @Autowired
    GroupMappingService groupMappings;

    @Autowired
    AccessChanges accessChanges;

    @Autowired
    AccessChangeLog log;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    IdentityProviderCatalog catalog;

    private UUID actor;
    private UUID subject;
    private Instant start;

    @BeforeEach
    void signIn() {
        start = Instant.now().minusSeconds(1);
        actor = OperatorFixture.signIn(users, roles, rolePermissions, userRoles, grants);
        AppUserEntity other = AppUserEntity.local("subject-" + UUID.randomUUID(), null, "{noop}x");
        other.setMustChangePassword(false);
        subject = users.save(other).getId();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private List<UUID> subjectsLogged() {
        return jdbc.query(
                "SELECT subject_id FROM access_change_log WHERE actor_id = ? ORDER BY id",
                (rs, i) -> rs.getObject(1, UUID.class),
                actor);
    }

    private UUID aRole() {
        return roleService
                .create(new RoleRequest("log-role-" + UUID.randomUUID(), List.of(Permissions.USER_ADMIN), false, false))
                .id();
    }

    @Test
    void aRoleChangeIsLoggedForEveryone() {
        UUID role = aRole();
        assertThat(subjectsLogged()).containsExactly((UUID) null);

        roleService.update(role, new RoleRequest("log-renamed-" + UUID.randomUUID(), List.of(), false, false));

        assertThat(subjectsLogged()).containsExactly(null, null);
    }

    @Test
    void aGrantIsLoggedForTheUserItReaches() {
        UUID role = aRole();

        userService.addGrant(subject, new GrantRequest(role, "GLOBAL", null));
        userService.removeGrant(subject, role, "GLOBAL", null);

        assertThat(subjectsLogged()).containsExactly(null, subject, subject);
    }

    @Test
    void aTeamMemberAddedIsLogged() {
        UUID team = teamService.create("log-team-" + UUID.randomUUID()).id();

        teamService.addMember(
                team,
                new MemberRequest(
                        PrincipalType.USER,
                        subject,
                        null,
                        null,
                        roles.findByName("TEAM_ADMIN").orElseThrow().getId()));

        assertThat(subjectsLogged()).hasSize(2);
        assertThat(log.changedOthersSince(actor, start)).isTrue();
    }

    @Test
    void aGroupMappingAndTheDefaultRoleAreLogged() {
        doReturn(List.of(new IdentityProviderListing.Entry("log-sso", IdentityProviderListing.REDIRECT, "SSO", "/sso")))
                .when(catalog)
                .providers();
        UUID role = aRole();

        UUID mapping = groupMappings
                .create("log-sso", new GroupMappingRequest("ops", role, "GLOBAL", null))
                .id();
        groupMappings.setDefaultRole("log-sso", role);
        groupMappings.delete("log-sso", mapping);

        assertThat(subjectsLogged()).hasSize(4);
    }

    @Test
    void changingOnlyOneselfDoesNotCountAsChangingOthers() {
        accessChanges.changedFor(actor);

        assertThat(subjectsLogged()).containsExactly(actor);
        assertThat(log.changedOthersSince(actor, start)).isFalse();

        accessChanges.changedFor(subject);

        assertThat(log.changedOthersSince(actor, start)).isTrue();
        assertThat(log.changedOthersSince(actor, Instant.now().plusSeconds(60))).isFalse();
        assertThat(log.changedOthersSince(subject, start)).isFalse();
    }

    @Test
    void aChangeWithNoUserBehindItWritesNothing() {
        SecurityContextHolder.clearContext();
        long before = jdbc.queryForObject("SELECT count(*) FROM access_change_log", Long.class);

        accessChanges.changedFor(subject);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_change_log", Long.class))
                .isEqualTo(before);
    }
}
