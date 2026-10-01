package io.github.sudoitir.artemisstudio.feature.plugins.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * License files end to end (ADR-0153), through the real security chain and real plugin runtimes: an
 * installer uploads, replaces and removes a plugin's license and sees the plugin's verdict; the plugin
 * is told of each change and reads only its own file; every refusal leaves the stored state alone and
 * is audited without the file's content; and a plugin that fails on its license affects no other.
 *
 * <p>The fixture plugin judges a file by its first word: {@code valid} is accepted, {@code boom}
 * makes its listener throw, anything else is invalid. It leaves what it saw in system properties,
 * which every class loader shares, so the test can look into the plugin.
 */
class PluginLicenseIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String SECRET = "valid-SECRET-marker-4f1c9";

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
    PasswordEncoder passwordEncoder;

    @Autowired
    PluginInstallers installers;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    private final List<String> pluginIds = new ArrayList<>();
    private final List<UUID> installerIds = new ArrayList<>();

    @BeforeEach
    void trustThePublisher() {
        TrustedTestKey.trust(jdbc);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM plugin_trusted_key WHERE fingerprint <> ?", TestSigningKeys.PUBLISHER.fingerprint());
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
            jdbc.update("DELETE FROM plugin_license WHERE plugin_id = ?", id);
            jdbc.update("DELETE FROM plugin_install WHERE id = ?", id);
            jdbc.update("DELETE FROM plugin_upload WHERE plugin_id = ?", id);
            System.clearProperty("probe." + id + ".removed");
            System.clearProperty("probe." + id + ".foreign");
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

    private MockHttpSession installerSession(MockMvc mvc, UUID[] userId) throws Exception {
        String username = unique("licenser");
        userId[0] = administrator(username);
        installers.grant(userId[0], "test");
        installerIds.add(userId[0]);
        return signIn(mvc, username);
    }

    /** A plugin that needs a license and judges the one it is given by its first word. */
    private byte[] jar(String id, boolean requiresLicense) throws Exception {
        String pkg = "com.acme." + id.replace('-', '_');
        PluginJarBuilder builder = new PluginJarBuilder(id);
        if (requiresLicense) {
            builder.descriptorField("requiresLicense", true).source(pkg + ".LicenseProbe", """
                    package %s;
                    import io.github.sudoitir.artemisstudio.kernel.plugin.PluginLicense;
                    import io.github.sudoitir.artemisstudio.kernel.plugin.PluginLicenseChanged;
                    import java.nio.charset.StandardCharsets;
                    import java.time.Duration;
                    import java.time.Instant;
                    import org.springframework.context.event.EventListener;
                    import org.springframework.stereotype.Component;
                    @Component
                    public class LicenseProbe {
                        private static final String ID = "%s";
                        private final PluginLicense license;
                        LicenseProbe(PluginLicense license) { this.license = license; }
                        @EventListener
                        public void on(PluginLicenseChanged changed) {
                            var file = license.file();
                            if (!changed.pluginId().equals(ID)) {
                                if (file.isPresent()) System.setProperty("probe." + ID + ".foreign", "saw a file");
                                return;
                            }
                            if (file.isEmpty()) { System.setProperty("probe." + ID + ".removed", "true"); return; }
                            String text = new String(file.get().content(), StandardCharsets.UTF_8);
                            if (text.startsWith("boom")) throw new IllegalStateException("probe failed");
                            license.report(file.get().sha256(), text.startsWith("valid")
                                    ? new PluginLicense.Verdict(PluginLicense.Status.VALID,
                                            Instant.now().plus(Duration.ofDays(400)), "Acme Ltd", "accepted")
                                    : new PluginLicense.Verdict(PluginLicense.Status.INVALID, null, null, "not accepted"));
                        }
                    }
                    """.formatted(pkg, id));
        }
        return java.nio.file.Files.readAllBytes(builder.build());
    }

    private void install(MockMvc mvc, MockHttpSession session, String id, boolean requiresLicense) throws Exception {
        pluginIds.add(id);
        String body = mvc.perform(put("/api/v1/admin/plugins/upload")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(jar(id, requiresLicense)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode plan = json.readTree(body);
        assertThat(plan.at("/plan/info/requiresLicense").asBoolean()).isEqualTo(requiresLicense);
        mvc.perform(post(
                                "/api/v1/admin/plugins/uploads/{sha}/activate",
                                plan.get("sha256").asString())
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isAccepted());
        await(id + " is active")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> view(mvc, session, id).path("status").asString(), "active"::equals);
    }

    private JsonNode view(MockMvc mvc, MockHttpSession session, String id) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/admin/plugins/{id}", id).session(session))
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private void awaitLicense(MockMvc mvc, MockHttpSession session, String id, String state) {
        await(id + " license is " + state)
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> view(mvc, session, id).at("/license/state").asString(), state::equals);
    }

    private org.springframework.test.web.servlet.ResultActions putLicense(
            MockMvc mvc, MockHttpSession session, String id, byte[] content, MediaType type) throws Exception {
        return mvc.perform(put("/api/v1/admin/plugins/{id}/license", id)
                .session(session)
                .with(csrf())
                .contentType(type)
                .content(content));
    }

    private java.util.Map<String, Object> lastAudit(String action, UUID userId) {
        return jdbc.queryForMap(
                "SELECT outcome, error, params::text AS params, target_name FROM audit_event"
                        + " WHERE action = ? AND user_id = ? ORDER BY id DESC LIMIT 1",
                action,
                userId);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void anInstallerUploadsReplacesAndRemovesALicenseAndSeesTheVerdictOfThePlugin() throws Exception {
        MockMvc mvc = mvc();
        UUID[] userId = new UUID[1];
        MockHttpSession session = installerSession(mvc, userId);
        String id = unique("acme-licensed");
        install(mvc, session, id, true);

        // Declared, nothing uploaded yet.
        mvc.perform(get("/api/v1/admin/plugins/{id}", id).session(session))
                .andExpect(jsonPath("$.info.requiresLicense").value(true))
                .andExpect(jsonPath("$.license.state").value("MISSING"));

        // Upload: stored, the plugin is told, reads it and reports.
        String body = putLicense(mvc, session, id, bytes(SECRET), MediaType.APPLICATION_OCTET_STREAM)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadedBy").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        awaitLicense(mvc, session, id, "VALID");
        JsonNode license = view(mvc, session, id).path("license");
        assertThat(license.path("licensee").asString()).isEqualTo("Acme Ltd");
        assertThat(license.path("detail").asString()).isEqualTo("accepted");
        assertThat(license.path("expiresAt").isString()).isTrue();
        assertThat(license.path("reportedAt").isString()).isTrue();

        // Audited with the plugin, the hash and the size, never the content.
        var audit = lastAudit("PLUGIN_LICENSE_UPLOAD", userId[0]);
        assertThat(audit).containsEntry("outcome", "SUCCESS").containsEntry("target_name", id);
        assertThat(audit.get("params").toString())
                .contains("sha256", String.valueOf(SECRET.length()))
                .doesNotContain(SECRET);
        assertThat(body).doesNotContain(SECRET);

        // Replace: the old verdict is gone, the plugin judges the new file.
        putLicense(mvc, session, id, bytes("expired-or-rather-invalid"), MediaType.APPLICATION_OCTET_STREAM)
                .andExpect(status().isOk());
        awaitLicense(mvc, session, id, "INVALID");
        assertThat(view(mvc, session, id).at("/license/detail").asString()).isEqualTo("not accepted");

        // Remove: the plugin is told and finds none.
        mvc.perform(delete("/api/v1/admin/plugins/{id}/license", id)
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isNoContent());
        await("the plugin was told")
                .atMost(Duration.ofSeconds(15))
                .until(() -> "true".equals(System.getProperty("probe." + id + ".removed")));
        awaitLicense(mvc, session, id, "MISSING");
        assertThat(lastAudit("PLUGIN_LICENSE_REMOVE", userId[0])).containsEntry("outcome", "SUCCESS");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE params::text LIKE ?",
                        Integer.class,
                        "%" + SECRET + "%"))
                .isZero();
    }

    @Test
    void aPluginThatDeclaresNoLicenseShowsNoneAndRefusesOne() throws Exception {
        MockMvc mvc = mvc();
        UUID[] userId = new UUID[1];
        MockHttpSession session = installerSession(mvc, userId);
        String id = unique("acme-plain");
        install(mvc, session, id, false);

        mvc.perform(get("/api/v1/admin/plugins/{id}", id).session(session))
                .andExpect(jsonPath("$.info.requiresLicense").value(false))
                .andExpect(jsonPath("$.license").doesNotExist());
        putLicense(mvc, session, id, bytes(SECRET), MediaType.APPLICATION_OCTET_STREAM)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].code").value("license-not-required"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plugin_license WHERE plugin_id = ?", Integer.class, id))
                .isZero();
        assertThat(lastAudit("PLUGIN_LICENSE_UPLOAD", userId[0])).containsEntry("outcome", "FAILURE");
    }

    @Test
    void anEmptyOversizedOrMistypedUploadIsRefusedAndLeavesTheStoredFileAlone() throws Exception {
        MockMvc mvc = mvc();
        UUID[] userId = new UUID[1];
        MockHttpSession session = installerSession(mvc, userId);
        String id = unique("acme-refusals");
        install(mvc, session, id, true);
        putLicense(mvc, session, id, bytes(SECRET), MediaType.APPLICATION_OCTET_STREAM)
                .andExpect(status().isOk());
        awaitLicense(mvc, session, id, "VALID");
        String stored = jdbc.queryForObject("SELECT sha256 FROM plugin_license WHERE plugin_id = ?", String.class, id);

        putLicense(mvc, session, id, new byte[0], MediaType.APPLICATION_OCTET_STREAM)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].code").value("license-empty"));
        putLicense(mvc, session, id, new byte[64 * 1024 + 1], MediaType.APPLICATION_OCTET_STREAM)
                .andExpect(status().isPayloadTooLarge());
        putLicense(mvc, session, id, bytes("{\"format\":1}"), MediaType.APPLICATION_JSON)
                .andExpect(status().isUnsupportedMediaType());
        putLicense(mvc, session, id, bytes("a=b"), MediaType.TEXT_PLAIN).andExpect(status().isUnsupportedMediaType());

        assertThat(jdbc.queryForObject("SELECT sha256 FROM plugin_license WHERE plugin_id = ?", String.class, id))
                .isEqualTo(stored);
        assertThat(view(mvc, session, id).at("/license/state").asString()).isEqualTo("VALID");
        // Exactly 64 KiB is allowed.
        putLicense(mvc, session, id, new byte[64 * 1024], MediaType.APPLICATION_OCTET_STREAM)
                .andExpect(status().isOk());
    }

    @Test
    void aNonInstallerOrAStaleSessionCannotUploadOrRemoveAndIsAudited() throws Exception {
        MockMvc mvc = mvc();
        UUID[] userId = new UUID[1];
        MockHttpSession installer = installerSession(mvc, userId);
        String id = unique("acme-guarded");
        install(mvc, installer, id, true);
        putLicense(mvc, installer, id, bytes(SECRET), MediaType.APPLICATION_OCTET_STREAM)
                .andExpect(status().isOk());
        awaitLicense(mvc, installer, id, "VALID");
        String stored = jdbc.queryForObject("SELECT sha256 FROM plugin_license WHERE plugin_id = ?", String.class, id);

        // An administrator who is not an installer.
        String name = unique("admin-not-installer");
        UUID adminId = administrator(name);
        MockHttpSession admin = signIn(mvc, name);
        putLicense(mvc, admin, id, bytes("valid-other"), MediaType.APPLICATION_OCTET_STREAM)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("plugin-installer-required")));
        mvc.perform(delete("/api/v1/admin/plugins/{id}/license", id)
                        .session(admin)
                        .with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(lastAudit("PLUGIN_LICENSE_UPLOAD", adminId)).containsEntry("outcome", "FAILURE");
        assertThat(lastAudit("PLUGIN_LICENSE_REMOVE", adminId)).containsEntry("outcome", "FAILURE");

        // An installer whose sign-in is older than the step-up window.
        var facts = (SessionFacts) installer.getAttribute(SessionAuthentication.FACTS_ATTRIBUTE);
        installer.setAttribute(
                SessionAuthentication.FACTS_ATTRIBUTE,
                facts.withAuthenticatedAt(Instant.now().minusSeconds(600)));
        putLicense(mvc, installer, id, bytes("valid-other"), MediaType.APPLICATION_OCTET_STREAM)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("reauthentication-required")));
        mvc.perform(delete("/api/v1/admin/plugins/{id}/license", id)
                        .session(installer)
                        .with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(lastAudit("PLUGIN_LICENSE_UPLOAD", userId[0])).containsEntry("outcome", "FAILURE");
        assertThat(lastAudit("PLUGIN_LICENSE_REMOVE", userId[0])).containsEntry("outcome", "FAILURE");

        assertThat(jdbc.queryForObject("SELECT sha256 FROM plugin_license WHERE plugin_id = ?", String.class, id))
                .isEqualTo(stored);
    }

    @Test
    void aPluginSeesOnlyItsOwnFileAndOneThatFailsOnItsLicenseAffectsNoOther() throws Exception {
        MockMvc mvc = mvc();
        UUID[] userId = new UUID[1];
        MockHttpSession session = installerSession(mvc, userId);
        String broken = unique("acme-broken");
        String bystander = unique("acme-bystander");
        install(mvc, session, broken, true);
        install(mvc, session, bystander, true);

        // The broken plugin's listener throws on its file.
        putLicense(mvc, session, broken, bytes("boom"), MediaType.APPLICATION_OCTET_STREAM)
                .andExpect(status().isOk());
        Thread.sleep(1500);
        assertThat(view(mvc, session, broken).path("status").asString()).isEqualTo("active");
        assertThat(view(mvc, session, broken).at("/license/state").asString()).isEqualTo("UNCHECKED");

        // The other plugin was told, found no file of its own, and works as before.
        assertThat(System.getProperty("probe." + bystander + ".foreign"))
                .as("the other plugin could not read the file")
                .isNull();
        assertThat(view(mvc, session, bystander).path("status").asString()).isEqualTo("active");
        assertThat(view(mvc, session, bystander).at("/license/state").asString())
                .isEqualTo("MISSING");
        putLicense(mvc, session, bystander, bytes("valid-bystander"), MediaType.APPLICATION_OCTET_STREAM)
                .andExpect(status().isOk());
        awaitLicense(mvc, session, bystander, "VALID");

        // Studio still lists everything; an invalid license changes only the one plugin.
        putLicense(mvc, session, broken, bytes("nonsense"), MediaType.APPLICATION_OCTET_STREAM)
                .andExpect(status().isOk());
        awaitLicense(mvc, session, broken, "INVALID");
        assertThat(view(mvc, session, bystander).at("/license/state").asString())
                .isEqualTo("VALID");
        mvc.perform(get("/api/v1/admin/plugins").session(session)).andExpect(status().isOk());
    }
}
