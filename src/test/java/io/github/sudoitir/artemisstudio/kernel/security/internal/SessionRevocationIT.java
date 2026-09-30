package io.github.sudoitir.artemisstudio.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.GrantRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.RoleRequest;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * Taking access away ends the affected users' sessions (identity-and-sessions spec, ADR-0123),
 * against the real JDBC session store: a session carries the grants resolved at sign-in, so a
 * surviving one would keep the old rights until it timed out.
 */
class SessionRevocationIT extends PostgresIntegrationTest {

    @Autowired
    UserService userService;

    @Autowired
    RoleService roleService;

    @Autowired
    AppUserRepository users;

    @Autowired
    SessionTerminator terminator;

    @Autowired
    FindByIndexNameSessionRepository<? extends Session> sessions;

    @BeforeEach
    void signInAsAdministrator() {
        var admin = new StudioPrincipal(
                UUID.randomUUID(),
                "revocation-admin",
                Set.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of(Permissions.USER_ADMIN))),
                false);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void disablingAUserEndsTheirSessions() {
        AppUserEntity user = newUser("revoke-disabled");
        openSession(user.getUsername());
        openSession(user.getUsername());
        String bystander = openSession("revoke-bystander");

        userService.setDisabled(user.getId(), true);

        assertThat(sessions.findByPrincipalName(user.getUsername())).isEmpty();
        assertThat(sessions.findById(bystander)).isNotNull();
    }

    @Test
    void removingAGrantEndsTheUsersSessions() {
        AppUserEntity user = newUser("revoke-grant");
        var role = roleService.create(new RoleRequest("revoke-grant-role", List.of("cluster:read")));
        userService.addGrant(user.getId(), new GrantRequest(role.id(), "GLOBAL", null));
        openSession(user.getUsername());

        userService.removeGrant(user.getId(), role.id(), "GLOBAL", null);

        assertThat(sessions.findByPrincipalName(user.getUsername())).isEmpty();
    }

    @Test
    void changingARolesPermissionsEndsEveryMembersSessions() {
        AppUserEntity first = newUser("revoke-role-a");
        AppUserEntity second = newUser("revoke-role-b");
        var role = roleService.create(new RoleRequest("revoke-role", List.of("cluster:read", "queue:purge")));
        userService.addGrant(first.getId(), new GrantRequest(role.id(), "GLOBAL", null));
        userService.addGrant(second.getId(), new GrantRequest(role.id(), "GLOBAL", null));
        openSession(first.getUsername());
        openSession(second.getUsername());

        roleService.update(role.id(), new RoleRequest("revoke-role", List.of("cluster:read")));

        assertThat(sessions.findByPrincipalName(first.getUsername())).isEmpty();
        assertThat(sessions.findByPrincipalName(second.getUsername())).isEmpty();
    }

    @Test
    void endingAllButOneSessionKeepsThatSession() {
        String keep = openSession("revoke-except");
        openSession("revoke-except");
        String bystander = openSession("revoke-except-bystander");

        terminator.endSessionsOfExcept("revoke-except", Set.of(keep));

        assertThat(sessions.findByPrincipalName("revoke-except")).containsOnlyKeys(keep);
        assertThat(sessions.findById(bystander)).isNotNull();
    }

    @Test
    void addingAGrantLeavesSessionsAlone() {
        AppUserEntity user = newUser("revoke-add");
        var role = roleService.create(new RoleRequest("revoke-add-role", List.of("cluster:read")));
        openSession(user.getUsername());

        userService.addGrant(user.getId(), new GrantRequest(role.id(), "GLOBAL", null));

        assertThat(sessions.findByPrincipalName(user.getUsername())).hasSize(1);
    }

    private AppUserEntity newUser(String username) {
        AppUserEntity user = AppUserEntity.local(username, username + "@example.test", "{noop}unused");
        user.setMustChangePassword(false);
        return users.save(user);
    }

    private String openSession(String username) {
        return open(sessions, username);
    }

    private static <S extends Session> String open(FindByIndexNameSessionRepository<S> repository, String username) {
        S session = repository.createSession();
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, username);
        repository.save(session);
        return session.getId();
    }
}
