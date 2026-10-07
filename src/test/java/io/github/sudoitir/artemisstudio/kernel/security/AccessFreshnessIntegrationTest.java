package io.github.sudoitir.artemisstudio.kernel.security;

import static io.github.sudoitir.artemisstudio.support.SignedInSession.authentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.kernel.security.internal.RoleService;
import io.github.sudoitir.artemisstudio.kernel.security.internal.TeamService;
import io.github.sudoitir.artemisstudio.kernel.security.internal.UserService;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.PrincipalType;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.GrantRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.RoleRequest;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * What a signed-in session may do follows the database, not the sign-in: a role granted, or a team
 * joined, applies to the very next request of a session that was already open (authorization spec).
 */
class AccessFreshnessIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    UserService userService;

    @Autowired
    RoleService roleService;

    @Autowired
    TeamService teamService;

    MockMvc mvc;
    UUID userId;
    UsernamePasswordAuthenticationToken session;

    @BeforeEach
    void signIn() {
        mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        String name = "fresh-" + UUID.randomUUID();
        AppUserEntity user = AppUserEntity.local(name, null, "{noop}x");
        user.setMustChangePassword(false);
        userId = users.save(user).getId();
        StudioPrincipal principal = StudioPrincipal.live(userId, name, false);
        session = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private int status(String path) throws Exception {
        return mvc.perform(get(path).with(authentication(session)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    /** A role that administers users and nothing else, so granting and removing it is never "the last administrator". */
    private UUID userAdminRole() {
        asAdministrator();
        return roleService
                .create(new RoleRequest(
                        "user-admin-" + UUID.randomUUID(), List.of(Permissions.USER_ADMIN), false, false))
                .id();
    }

    private void asAdministrator() {
        new AdminAuthenticationExtension().beforeEach(null);
    }

    @Test
    void aRoleGrantedToASignedInUserAppliesToTheirNextRequest() throws Exception {
        assertThat(status("/api/v1/users")).isEqualTo(403);

        UUID role = userAdminRole();
        asAdministrator();
        userService.addGrant(userId, new GrantRequest(role, "GLOBAL", null));

        assertThat(status("/api/v1/users")).isEqualTo(200);

        asAdministrator();
        userService.removeGrant(userId, role, "GLOBAL", null);

        assertThat(status("/api/v1/users")).isEqualTo(403);
    }

    @Test
    void joiningATeamAsItsAdministratorAppliesToTheNextRequestToo() throws Exception {
        asAdministrator();
        UUID team = teamService.create("fresh-" + UUID.randomUUID()).id();
        String listed = mvc.perform(get("/api/v1/teams").with(authentication(session)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listed).doesNotContain(team.toString());

        asAdministrator();
        teamService.addMember(
                team,
                new MemberRequest(
                        PrincipalType.USER,
                        userId,
                        null,
                        null,
                        roles.findByName("TEAM_ADMIN").orElseThrow().getId()));

        listed = mvc.perform(get("/api/v1/teams").with(authentication(session)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listed).contains(team.toString());
    }

    @Test
    void disablingTheAccountEndsItsAccessAtOnce() throws Exception {
        UUID role = userAdminRole();
        asAdministrator();
        userService.addGrant(userId, new GrantRequest(role, "GLOBAL", null));
        assertThat(status("/api/v1/users")).isEqualTo(200);

        asAdministrator();
        userService.disable(userId);

        assertThat(status("/api/v1/users")).isNotEqualTo(200);
    }
}
