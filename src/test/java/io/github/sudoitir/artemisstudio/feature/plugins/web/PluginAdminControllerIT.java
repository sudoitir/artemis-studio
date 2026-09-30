package io.github.sudoitir.artemisstudio.feature.plugins.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallers;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.TestSigningKeys;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.TrustedTestKey;
import io.github.sudoitir.artemisstudio.kernel.security.GrantLoader;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts;
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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
    private final java.util.List<UUID> installerIds = new java.util.ArrayList<>();

    @BeforeEach
    void trustThePublisher() {
        TrustedTestKey.trust(jdbc);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM plugin_trusted_key WHERE fingerprint <> ?", TestSigningKeys.PUBLISHER.fingerprint());
        jdbc.update("UPDATE plugin_trust_policy SET allow_unverified = false WHERE id = 1");
        for (UUID installer : installerIds) {
            jdbc.update("DELETE FROM plugin_installer WHERE user_id = ?", installer);
        }
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
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plan.pluginId").value(id))
                .andExpect(jsonPath("$.plan.activationClass").value("INSTANT"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String sha = json.readTree(body).get("sha256").asString();

        // A sign-in more than five minutes old must be confirmed first.
        var facts = (SessionFacts) session.getAttribute(SessionAuthentication.FACTS);
        session.setAttribute(
                SessionAuthentication.FACTS,
                facts.withAuthenticatedAt(Instant.now().minusSeconds(600)));
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

    // ---- trusted keys, the allowance and acknowledgement (design.md §5, §6) ------------------------

    private MockHttpSession installerSession(MockMvc mvc, UUID[] userId) throws Exception {
        String username = unique("trust-installer");
        userId[0] = administrator(username);
        installers.grant(userId[0], "test");
        installerIds.add(userId[0]);
        return signIn(mvc, username);
    }

    private String upload(MockMvc mvc, MockHttpSession session, byte[] jar) throws Exception {
        String body = mvc.perform(put("/api/v1/admin/plugins/upload")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(jar))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body).get("sha256").asString();
    }

    private java.util.Map<String, Object> lastAudit(String action, UUID userId) {
        return jdbc.queryForMap(
                "SELECT outcome, error, params::text AS params, target_name FROM audit_event"
                        + " WHERE action = ? AND user_id = ? ORDER BY id DESC LIMIT 1",
                action,
                userId);
    }

    @Test
    void aNonInstallerAddingAKeyIsRefusedAndAudited() throws Exception {
        String username = unique("key-admin-not-installer");
        UUID userId = administrator(username);
        MockMvc mvc = mvc();
        MockHttpSession session = signIn(mvc, username);

        mvc.perform(post("/api/v1/admin/plugins/keys")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme\",\"pem\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("plugin-installer-required")));
        mvc.perform(get("/api/v1/admin/plugins/keys").session(session)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/plugins/trust-policy")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowUnverified\":true}"))
                .andExpect(status().isForbidden());

        assertThat(lastAudit("PLUGIN_KEY_ADD", userId)).containsEntry("outcome", "FAILURE");
        assertThat(lastAudit("PLUGIN_TRUST_POLICY", userId)).containsEntry("outcome", "FAILURE");
        assertThat(jdbc.queryForObject("SELECT allow_unverified FROM plugin_trust_policy", Boolean.class))
                .isFalse();
    }

    @Test
    void anInstallerWithoutFreshAuthenticationMustStepUpToChangeKeysOrPolicy() throws Exception {
        MockMvc mvc = mvc();
        UUID[] userId = new UUID[1];
        MockHttpSession session = installerSession(mvc, userId);
        var facts = (SessionFacts) session.getAttribute(SessionAuthentication.FACTS);
        session.setAttribute(
                SessionAuthentication.FACTS,
                facts.withAuthenticatedAt(Instant.now().minusSeconds(600)));

        // Listing needs the installer tier only.
        mvc.perform(get("/api/v1/admin/plugins/keys").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/plugins/keys")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme\",\"pem\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("reauthentication-required")));
        mvc.perform(delete("/api/v1/admin/plugins/keys/{fp}", TestSigningKeys.PUBLISHER.fingerprint())
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/plugins/trust-policy")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowUnverified\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("reauthentication-required")));

        assertThat(lastAudit("PLUGIN_KEY_ADD", userId[0])).containsEntry("outcome", "FAILURE");
        assertThat(lastAudit("PLUGIN_KEY_REMOVE", userId[0])).containsEntry("outcome", "FAILURE");
        assertThat(lastAudit("PLUGIN_TRUST_POLICY", userId[0])).containsEntry("outcome", "FAILURE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plugin_trusted_key", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT allow_unverified FROM plugin_trust_policy", Boolean.class))
                .isFalse();
    }

    @Test
    void aKeyIsTakenFromTheStoredUploadNeverFromTheClient() throws Exception {
        MockMvc mvc = mvc();
        UUID[] userId = new UUID[1];
        MockHttpSession session = installerSession(mvc, userId);
        String id = unique("acme-other");
        pluginIds.add(id);
        String sha = upload(
                mvc,
                session,
                Files.readAllBytes(new PluginJarBuilder(id)
                        .signedBy(TestSigningKeys.OTHER_RESOURCE)
                        .build()));
        String other = TestSigningKeys.OTHER.fingerprint();
        String publisherPem = "-----BEGIN PUBLIC KEY-----\n"
                + java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                        .encodeToString(TestSigningKeys.PUBLISHER
                                .certificate()
                                .getPublicKey()
                                .getEncoded())
                + "\n-----END PUBLIC KEY-----";

        mvc.perform(get("/api/v1/admin/plugins/uploads/{sha}", sha).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trust.status").value("UNTRUSTED"))
                .andExpect(jsonPath("$.trust.fingerprint").value(other))
                .andExpect(jsonPath("$.acknowledgements").isEmpty());

        // Exactly one of upload and pem, and a name.
        mvc.perform(post("/api/v1/admin/plugins/keys")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("name", "Acme", "upload", sha, "pem", publisherPem))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/plugins/keys")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("name", "Acme"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/plugins/keys")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("name", "  ", "upload", sha))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plugin_trusted_key", Integer.class))
                .isEqualTo(1);

        mvc.perform(post("/api/v1/admin/plugins/keys")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("name", " Other Publisher ", "upload", sha))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fingerprint").value(other))
                .andExpect(jsonPath("$.name").value("Other Publisher"));
        assertThat(jdbc.queryForObject(
                        "SELECT public_key FROM plugin_trusted_key WHERE fingerprint = ?", byte[].class, other))
                .isEqualTo(TestSigningKeys.OTHER.certificate().getPublicKey().getEncoded());
        assertThat(lastAudit("PLUGIN_KEY_ADD", userId[0])).containsEntry("outcome", "SUCCESS");

        // The same key twice is a conflict; the review re-plans as trusted.
        mvc.perform(post("/api/v1/admin/plugins/keys")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("name", "Again", "upload", sha))))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/admin/plugins/uploads/{sha}", sha).session(session))
                .andExpect(jsonPath("$.trust.status").value("TRUSTED"))
                .andExpect(jsonPath("$.trust.keyName").value("Other Publisher"));

        mvc.perform(delete("/api/v1/admin/plugins/keys/{fp}", other)
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(lastAudit("PLUGIN_KEY_REMOVE", userId[0])).containsEntry("outcome", "SUCCESS");
        mvc.perform(delete("/api/v1/admin/plugins/keys/{fp}", other)
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aKeyFromAPemNeedsNoUploadAndAnUnsignedUploadYieldsNoKey() throws Exception {
        MockMvc mvc = mvc();
        UUID[] userId = new UUID[1];
        MockHttpSession session = installerSession(mvc, userId);
        String id = unique("acme-unsigned");
        pluginIds.add(id);
        String sha = upload(
                mvc,
                session,
                Files.readAllBytes(new PluginJarBuilder(id).unsigned().build()));

        mvc.perform(post("/api/v1/admin/plugins/keys")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("name", "Acme", "upload", sha))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].code").value("plugin-unsigned"));
        mvc.perform(post("/api/v1/admin/plugins/keys")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("name", "Acme", "pem", "garbage"))))
                .andExpect(status().isBadRequest());

        String pem = "-----BEGIN CERTIFICATE-----\n"
                + java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                        .encodeToString(TestSigningKeys.OTHER.certificate().getEncoded())
                + "\n-----END CERTIFICATE-----";
        mvc.perform(post("/api/v1/admin/plugins/keys")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("name", "Acme", "pem", pem))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fingerprint").value(TestSigningKeys.OTHER.fingerprint()));
    }

    @Test
    void theAllowanceIsAuditedAndKeysListWhatEachSigned() throws Exception {
        MockMvc mvc = mvc();
        UUID[] userId = new UUID[1];
        MockHttpSession session = installerSession(mvc, userId);
        String id = unique("acme-signed");
        pluginIds.add(id);
        String sha = upload(mvc, session, jar(id));
        mvc.perform(post("/api/v1/admin/plugins/uploads/{sha}/activate", sha)
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isAccepted());
        awaitStatus(mvc, session, id, "active");

        mvc.perform(put("/api/v1/admin/plugins/trust-policy")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowUnverified\":true}"))
                .andExpect(status().isNoContent());
        assertThat(lastAudit("PLUGIN_TRUST_POLICY", userId[0]))
                .containsEntry("outcome", "SUCCESS")
                .extractingByKey("params")
                .asString()
                .contains("true");

        String publisher = TestSigningKeys.PUBLISHER.fingerprint();
        mvc.perform(get("/api/v1/admin/plugins/keys").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowUnverified").value(true))
                .andExpect(jsonPath("$.keys[0].fingerprint").value(publisher))
                .andExpect(jsonPath("$.keys[0].name").value("Test publisher"))
                .andExpect(jsonPath("$.signedPlugins['" + publisher + "'][0]").value(id));
        mvc.perform(get("/api/v1/admin/plugins/{id}", id).session(session))
                .andExpect(jsonPath("$.signerFingerprint").value(publisher))
                .andExpect(jsonPath("$.verified").value(true));
    }

    @Test
    void anActivationThatNeedsAcknowledgementIsRefusedWithoutItAndAuditsTheUnverifiedDecision() throws Exception {
        MockMvc mvc = mvc();
        UUID[] userId = new UUID[1];
        MockHttpSession session = installerSession(mvc, userId);
        jdbc.update("UPDATE plugin_trust_policy SET allow_unverified = true WHERE id = 1");
        String id = unique("acme-ack");
        pluginIds.add(id);
        String sha = upload(
                mvc,
                session,
                Files.readAllBytes(new PluginJarBuilder(id).unsigned().build()));

        mvc.perform(get("/api/v1/admin/plugins/uploads/{sha}", sha).session(session))
                .andExpect(jsonPath("$.trust.status").value("UNSIGNED"))
                .andExpect(jsonPath("$.trust.allowed").value(true))
                .andExpect(jsonPath("$.acknowledgements[0]").value("unverified"));

        mvc.perform(post("/api/v1/admin/plugins/uploads/{sha}/activate", sha)
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.violations[0].code").value("acknowledgement-required"));
        assertThat(lastAudit("PLUGIN_ACTIVATE", userId[0])).containsEntry("outcome", "FAILURE");

        mvc.perform(post("/api/v1/admin/plugins/uploads/{sha}/activate?acknowledge=true", sha)
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isAccepted());
        awaitStatus(mvc, session, id, "active");
        assertThat(lastAudit("PLUGIN_ACTIVATE", userId[0]).get("params").toString())
                .contains("\"trust\": \"unverified\"");
        mvc.perform(get("/api/v1/admin/plugins/{id}", id).session(session))
                .andExpect(jsonPath("$.verified").value(false));
    }

    private void awaitStatus(MockMvc mvc, MockHttpSession session, String id, String want) {
        await(id + " reaches " + want)
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(
                        () -> json.readTree(mvc.perform(get("/api/v1/admin/plugins/{id}", id)
                                                .session(session))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                                .path("status")
                                .asString(),
                        want::equals);
    }
}
