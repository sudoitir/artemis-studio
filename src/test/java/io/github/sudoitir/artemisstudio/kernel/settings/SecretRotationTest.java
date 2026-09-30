package io.github.sudoitir.artemisstudio.kernel.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.KeyProvider;
import io.github.sudoitir.artemisstudio.kernel.security.Keyring;
import io.github.sudoitir.artemisstudio.kernel.security.PluginSecretStore;
import io.github.sudoitir.artemisstudio.kernel.security.PluginSecrets;
import io.github.sudoitir.artemisstudio.kernel.security.ReauthenticationRequiredException;
import io.github.sudoitir.artemisstudio.kernel.security.SealedStore;
import io.github.sudoitir.artemisstudio.kernel.security.SecretRotations;
import io.github.sudoitir.artemisstudio.kernel.security.SecretVault;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SettingsPermissions;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * Online key rotation (ADR-0132 D5): the sweep moves every sealed store to the new key while blobs stay readable,
 * resumes, catches stale writers, and a refused start is audited. The keyring holds versions 1 and 2, and the test
 * puts the shared database back on version 1 afterwards, since other contexts hold only that key.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class SecretRotationTest extends PostgresIntegrationTest {

    /** The key {@link PostgresIntegrationTest} supplies, as version 1. */
    private static final String KEY_1 = "YXJ0ZW1pcy1zdHVkaW8tdGVzdC1rZXktMzJieXRlcyE=";

    private static final String KEY_2 = Base64.getEncoder().encodeToString(new byte[32]);

    /** The provider holds two key versions, as after an operator added a newer one. */
    @TestConfiguration
    static class TwoKeys {

        @Bean
        @Primary
        KeyProvider twoKeys() {
            return new KeyProvider() {
                @Override
                public Keyring load() {
                    return new Keyring(new TreeMap<>(Map.of(
                            1, Keyring.parse("env", "key version 1", KEY_1),
                            2, Keyring.parse("env", "key version 2", KEY_2))));
                }

                @Override
                public Optional<String> secret(String name) {
                    return Optional.empty();
                }

                @Override
                public String name() {
                    return "env";
                }
            };
        }
    }

    @Autowired
    SecretRotations rotations;

    @Autowired
    SecretRotationService service;

    @Autowired
    SecretVault vault;

    @Autowired
    List<SealedStore> stores;

    @Autowired
    PluginSecretStore pluginSecrets;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    WebApplicationContext webContext;

    private final UUID clusterId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();
    private final UUID flowId = UUID.randomUUID();
    private final String plugin = "rot" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void startOnVersionOne() {
        jdbc.update("DELETE FROM secret_rotation");
        jdbc.update("DELETE FROM audit_event WHERE action = 'SECRET_ROTATION_START'");
        jdbc.update("UPDATE secret_key_state SET current_kek_version = 1");
        vault.refreshCurrentVersion();
        jdbc.update("INSERT INTO cluster (id, name) VALUES (?, ?)", clusterId, "rotation-" + clusterId);
    }

    @AfterEach
    void putBackOnVersionOne() {
        jdbc.update("DELETE FROM message_index WHERE cluster_id = ?", clusterId);
        jdbc.update("DELETE FROM notification_channel WHERE id = ?", channelId);
        jdbc.update("DELETE FROM plugin_secret WHERE plugin_id = ?", plugin);
        jdbc.update("DELETE FROM cluster WHERE id = ?", clusterId);
        jdbc.update("DELETE FROM secret_rotation");
        // Other rows in the shared database were rotated too; bring them back so a context holding only key 1 opens
        // them.
        for (SealedStore store : stores) {
            store.rewrapBatch(Integer.MAX_VALUE, 1, Integer.MAX_VALUE, blob -> vault.rewrap(blob, 1));
        }
        jdbc.update("UPDATE secret_key_state SET current_kek_version = 1");
        vault.refreshCurrentVersion();
    }

    /** One sealed value in every store, under the current version. Returns each blob's plaintext by AAD. */
    private Map<String, String> seedEveryStore() {
        String credentialAad = SecretVault.aad(clusterId, "PASSWORD");
        jdbc.update(
                "INSERT INTO broker_credential (kind, sealed, cluster_id) VALUES ('PASSWORD', ?, ?)",
                vault.seal(credentialAad, "broker-pw"),
                clusterId);
        String channelAad = "channel|" + channelId;
        jdbc.update(
                "INSERT INTO notification_channel (name, kind, config, id, sealed)"
                        + " VALUES (?, 'WEBHOOK', '{}'::jsonb, ?, ?)",
                "rot-" + channelId,
                channelId,
                vault.seal(channelAad, "channel-secret"));
        secretsOf(plugin).put("token", "plugin-secret");
        jdbc.update(
                "INSERT INTO message_index (observed_at, last_seen_at, message_id, queue_name, address, node_name,"
                        + " cluster_id, node_id, sealed) VALUES (now(), now(), 1, 'q', 'a', 'n', ?, ?, ?)",
                clusterId,
                UUID.randomUUID(),
                vault.seal("message|" + clusterId, "message-original"));
        jdbc.update(
                "INSERT INTO rr_flow (id, reply_kind, state, cluster_id) VALUES (?, 'SHARED_QUEUE', 'COMPLETED', ?)",
                flowId,
                clusterId);
        String eventAad = "rr|" + flowId;
        jdbc.update(
                "INSERT INTO rr_event (kind, detail, flow_id) VALUES ('REQUEST', jsonb_build_object('sealed', ?::text), ?)",
                Base64.getEncoder().encodeToString(vault.seal(eventAad, "rr-original")),
                flowId);
        return Map.of(
                credentialAad,
                "broker-pw",
                channelAad,
                "channel-secret",
                "message|" + clusterId,
                "message-original",
                eventAad,
                "rr-original");
    }

    private PluginSecrets secretsOf(String pluginId) {
        return (PluginSecrets) pluginSecrets.beansFor(pluginId).get("pluginSecrets");
    }

    private List<byte[]> blobs() {
        return List.of(
                jdbc.queryForObject(
                        "SELECT sealed FROM broker_credential WHERE cluster_id = ?", byte[].class, clusterId),
                jdbc.queryForObject("SELECT sealed FROM notification_channel WHERE id = ?", byte[].class, channelId),
                jdbc.queryForObject("SELECT sealed FROM plugin_secret WHERE plugin_id = ?", byte[].class, plugin),
                jdbc.queryForObject("SELECT sealed FROM message_index WHERE cluster_id = ?", byte[].class, clusterId),
                Base64.getDecoder()
                        .decode(jdbc.queryForObject(
                                "SELECT detail->>'sealed' FROM rr_event WHERE flow_id = ?", String.class, flowId)));
    }

    private static MockHttpServletRequest freshSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(SessionAuthentication.AUTHENTICATED_AT, Instant.now());
        return request;
    }

    private List<Map<String, Object>> startAudits() {
        return jdbc.queryForList(
                "SELECT outcome, error, params FROM audit_event WHERE action = 'SECRET_ROTATION_START'"
                        + " AND username = ? ORDER BY ts, id",
                "test-admin");
    }

    @Test
    void aRotationRewrapsEveryStoreAndEverySecretStillOpens() {
        Map<String, String> plain = seedEveryStore();
        assertThat(blobs())
                .allSatisfy(b -> assertThat(SecretVault.kekVersion(b)).isEqualTo(1));

        SecretRotations.Rotation started = service.start(freshSession());
        assertThat(started.status()).isEqualTo("RUNNING");
        assertThat(started.fromVersion()).isEqualTo(1);
        assertThat(started.toVersion()).isEqualTo(2);
        assertThat(vault.currentKekVersion()).isEqualTo(2);
        vault.seal("new", "written during the rotation");

        rotations.sweep();

        SecretRotations.Rotation done = rotations.last().orElseThrow();
        assertThat(done.status()).isEqualTo("SUCCEEDED");
        assertThat(done.remaining()).isZero();
        assertThat(done.rewrapped()).isGreaterThanOrEqualTo(5);
        assertThat(done.finishedAt()).isNotNull();
        assertThat(blobs())
                .allSatisfy(b -> assertThat(SecretVault.kekVersion(b)).isEqualTo(2));
        assertThat(vault.open("plugin|" + plugin + "|token", blobs().get(2))).isEqualTo("plugin-secret");
        assertThat(vault.open(SecretVault.aad(clusterId, "PASSWORD"), blobs().get(0)))
                .isEqualTo(plain.get(SecretVault.aad(clusterId, "PASSWORD")));
        assertThat(vault.open("channel|" + channelId, blobs().get(1))).isEqualTo("channel-secret");
        assertThat(vault.open("message|" + clusterId, blobs().get(3))).isEqualTo("message-original");
        assertThat(vault.open("rr|" + flowId, blobs().get(4))).isEqualTo("rr-original");
        assertThat(secretsOf(plugin).get("token")).contains("plugin-secret");
        stores.forEach(s -> assertThat(s.countBelow(2)).isZero());
        assertThat(startAudits())
                .singleElement()
                .satisfies(a -> assertThat(a.get("outcome")).isEqualTo("SUCCESS"));
    }

    @Test
    void aRotationStoppedMidWayResumesAndFinishes() {
        seedEveryStore();
        service.start(freshSession());

        // A pass that stopped after one row per store.
        for (SealedStore store : stores) {
            store.rewrapBatch(2, 2, 1, blob -> vault.rewrap(blob, 2));
        }
        assertThat(rotations.running()).isPresent();

        rotations.sweep();

        assertThat(rotations.running()).isEmpty();
        assertThat(rotations.last().orElseThrow().status()).isEqualTo("SUCCEEDED");
        assertThat(blobs())
                .allSatisfy(b -> assertThat(SecretVault.kekVersion(b)).isEqualTo(2));
    }

    @Test
    void aWriterThatSealedUnderTheOldVersionAfterTheStartIsSweptToo() {
        service.start(freshSession());
        secretsOf(plugin).put("stale", "written by a replica that has not refreshed");
        byte[] stale = vault.rewrap(
                jdbc.queryForObject("SELECT sealed FROM plugin_secret WHERE plugin_id = ?", byte[].class, plugin), 1);
        jdbc.update("UPDATE plugin_secret SET sealed = ? WHERE plugin_id = ?", stale, plugin);
        assertThat(SecretVault.kekVersion(stale)).isEqualTo(1);

        rotations.sweep();

        assertThat(rotations.last().orElseThrow().status()).isEqualTo("SUCCEEDED");
        assertThat(SecretVault.kekVersion(jdbc.queryForObject(
                        "SELECT sealed FROM plugin_secret WHERE plugin_id = ?", byte[].class, plugin)))
                .isEqualTo(2);
        assertThat(secretsOf(plugin).get("stale")).contains("written by a replica that has not refreshed");
    }

    @Test
    void aRotationIsRefusedWhenNoNewerKeyExistsOrOneIsRunning() {
        service.start(freshSession());
        assertThatThrownBy(() -> service.start(freshSession()))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).slug())
                .isEqualTo("rotation-running");

        rotations.sweep();

        assertThatThrownBy(() -> service.start(freshSession()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("newer key version")
                .extracting(e -> ((ConflictException) e).slug())
                .isEqualTo("no-newer-key");
        assertThat(startAudits()).extracting(a -> a.get("outcome")).containsExactly("SUCCESS", "FAILURE", "FAILURE");
    }

    @Test
    void aBlobThatCannotBeUnwrappedFailsTheRotationNamingTheStoreAndRowButNoValue() {
        secretsOf(plugin).put("broken", "never-shown-secret");
        jdbc.update(
                "UPDATE plugin_secret SET sealed = set_byte(sealed, 20, get_byte(sealed, 20) # 255) WHERE plugin_id = ?",
                plugin);
        UUID row = jdbc.queryForObject("SELECT id FROM plugin_secret WHERE plugin_id = ?", UUID.class, plugin);
        service.start(freshSession());

        rotations.sweep();

        SecretRotations.Rotation failed = rotations.last().orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error())
                .contains("plugin_secret")
                .contains(row.toString())
                .doesNotContain("never-shown-secret");
        assertThat(failed.finishedAt()).isNotNull();
        assertThat(rotations.running()).isEmpty();
    }

    @Test
    void aStaleAuthenticationIsRefusedAndAudited() {
        assertThatThrownBy(() -> service.start(new MockHttpServletRequest()))
                .isInstanceOf(ReauthenticationRequiredException.class);

        assertThat(rotations.running()).isEmpty();
        assertThat(vault.currentKekVersion()).isEqualTo(1);
        assertThat(startAudits()).singleElement().satisfies(a -> {
            assertThat(a.get("outcome")).isEqualTo("FAILURE");
            assertThat(a.get("params").toString()).contains("currentVersion");
        });
    }

    @Test
    void aCallerWithoutSettingsWriteIsRefusedAndAudited() {
        StudioPrincipal reader = new StudioPrincipal(
                null,
                "test-admin",
                Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of(SettingsPermissions.SETTINGS_READ))),
                false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(reader, null, reader.getAuthorities()));

        assertThatThrownBy(() -> service.start(freshSession())).isInstanceOf(AccessDeniedException.class);

        assertThat(rotations.running()).isEmpty();
        assertThat(startAudits())
                .singleElement()
                .satisfies(a -> assertThat(a.get("outcome")).isEqualTo("FAILURE"));
    }

    @Test
    void theStatusViewShowsVersionsAndCountsAndNoKeyMaterial() throws Exception {
        seedEveryStore();
        MockMvc mvc = webAppContextSetup(webContext).build();

        String before = mvc.perform(get("/api/v1/settings/secrets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("env"))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.availableVersions[0]").value(1))
                .andExpect(jsonPath("$.availableVersions[1]").value(2))
                .andExpect(jsonPath("$.countsByVersion['1']").isNumber())
                .andExpect(jsonPath("$.lastRotation").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        service.start(freshSession());
        rotations.sweep();

        String after = mvc.perform(get("/api/v1/settings/secrets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.lastRotation.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.lastRotation.fromVersion").value(1))
                .andExpect(jsonPath("$.lastRotation.toVersion").value(2))
                .andExpect(jsonPath("$.lastRotation.startedBy").value("test-admin"))
                .andExpect(jsonPath("$.lastRotation.error").doesNotExist())
                .andExpect(jsonPath("$.countsByVersion['2']").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(before + after).doesNotContain(KEY_1).doesNotContain(KEY_2);
    }
}
