package io.github.sudoitir.artemisstudio.feature.plugins.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallers;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.security.GrantLoader;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The plugin administration API against the real security chain (task 7.7, ADR-0103): the
 * installer tier is not a permission, a revocation bites on the next request, a stale session
 * must step up, a token caller never installs, a step-up that fails five times ends the session,
 * and uploads are rate-limited.
 */
class PluginAdminControllerIT extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    WebApplicationContext webContext;

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
    PasswordEncoder passwordEncoder;

    @Autowired
    PluginInstallers installers;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    private final java.util.List<String> pluginIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String id : pluginIds) {
            registry.get(id).ifPresent(slot -> {
                if (slot instanceof PluginRuntimeRegistry.Active active) {
                    active.runtime().close();
                }
            });
            registry.remove(id);
            jdbc.update("DELETE FROM plugin_install WHERE id = ?", id);
            jdbc.update("DELETE FROM plugin_upload WHERE plugin_id = ?", id);
        }
        jdbc.update("DELETE FROM plugin_artifact a WHERE NOT EXISTS (SELECT 1 FROM plugin_install i"
                + " WHERE i.sha256 = a.sha256 OR i.previous_sha256 = a.sha256)"
                + " AND NOT EXISTS (SELECT 1 FROM plugin_upload u WHERE u.sha256 = a.sha256)");
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
    }

    /** An account holding every permission, which on its own still does not let it install. */
    private UUID administrator(String username) {
        AppUserEntity user =
                AppUserEntity.local(username, username + "@example.test", passwordEncoder.encode(PASSWORD));
        user.setMustChangePassword(false);
        users.save(user);
        RoleEntity role = roles.save(new RoleEntity("role-" + UUID.randomUUID(), false));
        rolePermissions.save(new RolePermissionEntity(role.getId(), Permissions.WILDCARD));
        userRoles.save(new UserRoleEntity(
                user.getId(),
                role.getId(),
                "GLOBAL",
                io.github.sudoitir.artemisstudio.kernel.security.ScopeIds.GLOBAL));
        return user.getId();
    }

    private MockHttpSession signIn(MockMvc mvc, String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD)))
                .andExpect(status().isOk());
        return session;
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    private byte[] jar(String id) throws Exception {
        return Files.readAllBytes(new PluginJarBuilder(id).build());
    }

    @Test
    void anAdministratorWhoIsNotAnInstallerSeesWhyAndCannotUpload() throws Exception {
        String username = unique("admin-not-installer");
        administrator(username);
        MockMvc mvc = mvc();
        MockHttpSession session = signIn(mvc, username);

        mvc.perform(get("/api/v1/admin/plugins").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canInstall").value(false))
                .andExpect(jsonPath("$.cannotInstall.code").value("plugin-installer-required"));
        mvc.perform(put("/api/v1/admin/plugins/upload")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(jar(unique("acme-x"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("plugin-installer-required")));
    }

    @Test
    void anInstallerUploadsStepsUpAndActivatesAndARevocationBitesOnTheNextRequest() throws Exception {
        String username = unique("installer");
        UUID userId = administrator(username);
        installers.grant(userId, "test");
        MockMvc mvc = mvc();
        MockHttpSession session = signIn(mvc, username);
        String id = unique("acme-admin");
        pluginIds.add(id);

        String body = mvc.perform(put("/api/v1/admin/plugins/upload")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(jar(id)))
                .andDo(r -> System.out.println("DBGBODY " + r.getResponse().getContentAsString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plan.pluginId").value(id))
                .andExpect(jsonPath("$.plan.activationClass").value("INSTANT"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String sha = json.readTree(body).get("sha256").asString();

        // A sign-in more than five minutes old must be confirmed first.
        session.setAttribute(
                SessionAuthentication.AUTHENTICATED_AT, Instant.now().minusSeconds(600));
        mvc.perform(post("/api/v1/admin/plugins/uploads/{sha}/activate", sha)
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("reauthentication-required")));

        mvc.perform(post("/api/v1/auth/reauthenticate")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("reauthentication-failed")));
        String before = session.getId();
        var stepUp = mvc.perform(post("/api/v1/auth/reauthenticate")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("PASSWORD"))
                .andReturn();
        assertThat(stepUp.getRequest().getSession().getId())
                .as("step-up rotates the session id")
                .isNotEqualTo(before);

        mvc.perform(post("/api/v1/admin/plugins/uploads/{sha}/activate", sha)
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isAccepted());
        awaitStatus(mvc, session, id, "active");

        // Revoked in the database: the very next request is refused, with no sign-out needed.
        UUID other = administrator(unique("other-installer"));
        installers.grant(other, "test");
        assertThat(installers.revoke(userId)).isTrue();
        mvc.perform(post("/api/v1/admin/plugins/{id}/disable", id)
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("plugin-installer-required")));
        jdbc.update("DELETE FROM plugin_installer WHERE user_id = ?", other);
    }

    @Test
    void anApiTokenCallerNeverInstallsEvenAsAnInstaller() throws Exception {
        String username = unique("token-installer");
        UUID userId = administrator(username);
        installers.grant(userId, "test");
        StudioPrincipal viaToken = new StudioPrincipal(userId, username, grants.loadFor(userId), false, "ci-token");
        var auth = UsernamePasswordAuthenticationToken.authenticated(viaToken, null, viaToken.getAuthorities());

        mvc().perform(put("/api/v1/admin/plugins/upload")
                        .with(authentication(auth))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(jar(unique("acme-token"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("plugin-install-interactive-only")));
    }

    @Test
    void anUnauthenticatedUploadIsRefusedBeforeItsBodyIsRead() throws Exception {
        mvc().perform(put("/api/v1/admin/plugins/upload")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[] {1, 2, 3}))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fiveFailedStepUpsEndTheSession() throws Exception {
        String username = unique("stepup-lockout");
        administrator(username);
        MockMvc mvc = mvc();
        MockHttpSession session = signIn(mvc, username);

        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/v1/auth/reauthenticate")
                            .session(session)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"wrong\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/auth/reauthenticate")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void uploadsAreRateLimitedPerUser() throws Exception {
        String username = unique("rate-limited");
        UUID userId = administrator(username);
        installers.grant(userId, "test");
        MockMvc mvc = mvc();
        MockHttpSession session = signIn(mvc, username);

        for (int i = 0; i < 5; i++) {
            mvc.perform(put("/api/v1/admin/plugins/upload")
                            .session(session)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .content("not a jar".getBytes()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.violations").isArray());
        }
        mvc.perform(put("/api/v1/admin/plugins/upload")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("not a jar".getBytes()))
                .andExpect(status().isTooManyRequests());
        jdbc.update("DELETE FROM plugin_installer WHERE user_id = ?", userId);
    }

    @Test
    void anUploadOverFiftyMegabytesIsRefused() throws Exception {
        String username = unique("too-large");
        UUID userId = administrator(username);
        installers.grant(userId, "test");
        MockMvc mvc = mvc();
        MockHttpSession session = signIn(mvc, username);

        mvc.perform(put("/api/v1/admin/plugins/upload")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[(int) PluginAdminController.MAX_UPLOAD_BYTES + 1]))
                .andExpect(status().isPayloadTooLarge());
        jdbc.update("DELETE FROM plugin_installer WHERE user_id = ?", userId);
    }

    private void awaitStatus(MockMvc mvc, MockHttpSession session, String id, String want) throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        String last = null;
        while (Instant.now().isBefore(deadline)) {
            JsonNode plugin = json.readTree(
                    mvc.perform(get("/api/v1/admin/plugins/{id}", id).session(session))
                            .andReturn()
                            .getResponse()
                            .getContentAsString());
            last = plugin.path("status").asString();
            if (want.equals(last)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(id + " never reached " + want + "; last " + last);
    }
}
