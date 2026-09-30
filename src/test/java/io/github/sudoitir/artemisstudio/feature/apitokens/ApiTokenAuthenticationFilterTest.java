package io.github.sudoitir.artemisstudio.feature.apitokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Exercises {@link ApiTokenAuthenticationFilter} through the real
 * {@code SecurityFilterChain} (task 8.7) — the filter-ordering bug that let a
 * bearer token authenticate but then get silently wiped by
 * {@code SecurityContextHolderFilter} running after it was only visible at
 * this level; a unit test on the filter alone would not have caught it.
 */
class ApiTokenAuthenticationFilterTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ApiTokenService apiTokenService;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    UserRoleRepository userRoles;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
    }

    private AppUserEntity newUser(String username) {
        AppUserEntity user = AppUserEntity.local(username, username + "@example.test", "{noop}unused");
        user.setMustChangePassword(false);
        return users.save(user);
    }

    private void grantViewer(AppUserEntity user) {
        RoleEntity viewer = roles.findByName("VIEWER").orElseThrow();
        userRoles.save(new UserRoleEntity(user.getId(), viewer.getId(), "GLOBAL", ScopeIds.GLOBAL));
    }

    @Test
    void validTokenAuthenticatesAndSurvivesTheFilterChain() throws Exception {
        AppUserEntity user = newUser("token-valid");
        grantViewer(user);
        var minted = apiTokenService.mint(
                user.getId(),
                "ci",
                Instant.now().plusSeconds(3600),
                List.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("cluster:read"))),
                List.of());

        mvc().perform(get("/api/v1/clusters").header("Authorization", "Bearer " + minted.plaintext()))
                .andExpect(status().isOk());
    }

    @Test
    void tokenNotCoveringThePermissionIsForbidden() throws Exception {
        AppUserEntity user = newUser("token-narrow");
        grantViewer(user);
        var minted = apiTokenService.mint(
                user.getId(),
                "ci",
                Instant.now().plusSeconds(3600),
                List.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("cluster:read"))),
                List.of());

        // The token was minted with only cluster:read, so even reading environments
        // (which needs environment:read) is forbidden — narrowing is per-permission,
        // not "anything the owner can do".
        mvc().perform(get("/api/v1/environments").header("Authorization", "Bearer " + minted.plaintext()))
                .andExpect(status().isForbidden());

        mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/environments")
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"colour\":\"#000000\",\"sortOrder\":0}")
                        .header("Authorization", "Bearer " + minted.plaintext()))
                .andExpect(status().isForbidden());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        AppUserEntity user = newUser("token-expired");
        grantViewer(user);
        var minted = apiTokenService.mint(
                user.getId(),
                "ci",
                Instant.now().plusSeconds(3600),
                List.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("cluster:read"))),
                List.of());
        jdbc.sql("UPDATE api_token SET expires_at = now() - interval '1 minute' WHERE id = ?")
                .param(minted.entity().getId())
                .update();

        mvc().perform(get("/api/v1/clusters").header("Authorization", "Bearer " + minted.plaintext()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedTokenIsRejected() throws Exception {
        AppUserEntity user = newUser("token-revoked");
        grantViewer(user);
        var minted = apiTokenService.mint(
                user.getId(),
                "ci",
                Instant.now().plusSeconds(3600),
                List.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("cluster:read"))),
                List.of());
        apiTokenService.revoke(user.getId(), minted.entity().getId());

        mvc().perform(get("/api/v1/clusters").header("Authorization", "Bearer " + minted.plaintext()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongSecretForAKnownPrefixIsRejected() throws Exception {
        AppUserEntity user = newUser("token-wrong-secret");
        grantViewer(user);
        var minted = apiTokenService.mint(
                user.getId(),
                "ci",
                Instant.now().plusSeconds(3600),
                List.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("cluster:read"))),
                List.of());
        String prefix = minted.plaintext().substring(0, minted.plaintext().indexOf('_', 3));
        String tampered = prefix + "_" + "x".repeat(43);

        mvc().perform(get("/api/v1/clusters").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenNarrowsWhenOwnerLosesTheGrantAfterMinting() throws Exception {
        AppUserEntity user = newUser("token-owner-demoted");
        grantViewer(user);
        var minted = apiTokenService.mint(
                user.getId(),
                "ci",
                Instant.now().plusSeconds(3600),
                List.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("environment:read"))),
                List.of());

        mvc().perform(get("/api/v1/environments").header("Authorization", "Bearer " + minted.plaintext()))
                .andExpect(status().isOk());

        // Demote: strip every grant the owner holds, without disabling the account.
        userRoles.findByIdUserId(user.getId()).forEach(userRoles::delete);

        mvc().perform(get("/api/v1/environments").header("Authorization", "Bearer " + minted.plaintext()))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenNarrowsWhenOwnerIsDisabledAfterMinting() throws Exception {
        AppUserEntity user = newUser("token-owner-disabled");
        grantViewer(user);
        var minted = apiTokenService.mint(
                user.getId(),
                "ci",
                Instant.now().plusSeconds(3600),
                List.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("cluster:read"))),
                List.of());

        user.setDisabled(true);
        users.save(user);

        mvc().perform(get("/api/v1/clusters").header("Authorization", "Bearer " + minted.plaintext()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A signed-in browser session plus an Authorization header that authenticates nothing must not
     * skip CSRF: the header is refused with 401 before the session can authenticate the request.
     */
    @Test
    void aJunkAuthorizationHeaderNextToASessionIsRefusedNotAllowedPastCsrf() throws Exception {
        AppUserEntity user = newUser("csrf-junk-header");
        grantViewer(user);
        var principal = new io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal(
                user.getId(),
                user.getUsername(),
                Set.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("cluster:read"))),
                false);
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities()));
        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(
                org.springframework.security.web.context.HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY,
                context);
        // A real sign-in records these facts; a session without them counts as signed out.
        session.setAttribute(
                io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication.FACTS,
                io.github.sudoitir.artemisstudio.kernel.security.SessionFacts.signedIn(
                        new org.springframework.mock.web.MockHttpServletRequest()));

        mvc().perform(get("/api/v1/clusters").session(session)).andExpect(status().isOk());
        for (String header : List.of("Basic eDp4", "Bearer not-a-real-token", "x")) {
            mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                    "/api/v1/environments")
                            .session(session)
                            .header("Authorization", header)
                            .contentType("application/json")
                            .content("{\"name\":\"csrf-probe\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void garbageBearerValueIsRejectedNotCrashed() throws Exception {
        mvc().perform(get("/api/v1/clusters").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenPrincipalResolvedByAuthMeEndpoint() throws Exception {
        AppUserEntity user = newUser("token-me");
        grantViewer(user);
        var minted = apiTokenService.mint(
                user.getId(),
                "ci-name",
                Instant.now().plusSeconds(3600),
                List.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("cluster:read"))),
                List.of());

        String body = mvc().perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + minted.plaintext()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).contains(user.getUsername());
    }
}
