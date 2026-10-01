package io.github.sudoitir.artemisstudio.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginHost;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginArtifactRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.store.PluginStore;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.PluginTrust;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.SignInPlugin;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.SignInProbe;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.TestSigningKeys;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.TrustedTestKey;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.GroupMappingEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.GroupMappingRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plugins that sign users in, over real HTTP (ADR-0153): a signed fixture plugin installed through
 * {@link PluginHost} whose provider is answered by a {@link SignInProbe} directory, and the helpers
 * that sign its users in and map their groups.
 */
public abstract class SignInPluginIntegrationTest extends AccountIntegrationTest {

    protected static final String PUBLISHER = TestSigningKeys.PUBLISHER.fingerprint();
    protected static final String DIRECTORY_PASSWORD = "directory-secret";

    @Autowired
    protected PluginHost host;

    @Autowired
    protected PluginStore pluginStore;

    @Autowired
    protected PluginTrust trust;

    @Autowired
    protected PluginRuntimeRegistry registry;

    @Autowired
    protected PluginInstallRepository installs;

    @Autowired
    protected PluginArtifactRepository artifacts;

    @Autowired
    protected GroupMappingRepository mappings;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<String> pluginIds = new ArrayList<>();
    private final List<String> shas = new ArrayList<>();

    @BeforeEach
    void trustThePublisher() {
        trust.setAllowUnverified(false, "test");
        TrustedTestKey.trust(jdbcTemplate);
    }

    @AfterEach
    void removeSignInPlugins() {
        for (String id : pluginIds) {
            registry.get(id).ifPresent(slot -> {
                if (slot instanceof PluginRuntimeRegistry.Active active) {
                    active.runtime().close();
                }
            });
            registry.remove(id);
            installs.deleteById(id);
            SignInProbe.forget(SignInPlugin.providerId(id, "corp"));
            mappings.deleteAll(mappings.findAll().stream()
                    .filter(m -> m.getProviderId().startsWith(id + ":"))
                    .toList());
            users.deleteAll(users.findAll().stream()
                    .filter(u -> u.getProviderId().startsWith(id + ":"))
                    .toList());
        }
        pluginIds.clear();
        for (String sha : shas) {
            jdbc.sql("DELETE FROM plugin_upload WHERE sha256 = ?").param(sha).update();
            artifacts.deleteById(sha);
        }
        shas.clear();
        jdbc.sql("DELETE FROM plugin_trusted_key").update();
        trust.setAllowUnverified(false, "test");
    }

    protected static String newPluginId() {
        return "sso-" + Long.toString(System.nanoTime(), 36);
    }

    /** Installs and activates a signed plugin offering {@code <id>:corp}; returns its id. */
    protected String installSignInPlugin(boolean revalidates) throws Exception {
        String id = newPluginId();
        install(SignInPlugin.jar(id, revalidates), id);
        return id;
    }

    protected void install(PluginJarBuilder builder, String id) throws Exception {
        pluginIds.add(id);
        String sha = pluginStore.put(Files.readAllBytes(builder.build()));
        shas.add(sha);
        host.activate(sha, "tester", true);
        await("plugin '" + id + "' becomes active")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    var entity = installs.findById(id);
                    if (entity.isPresent() && entity.get().status() == PluginInstallStatus.FAILED) {
                        throw new AssertionError(
                                "Plugin '" + id + "' failed: " + entity.get().getFailure());
                    }
                    return entity.isPresent()
                            && entity.get().status() == PluginInstallStatus.ACTIVE
                            && entity.get().getSha256().equals(sha);
                });
    }

    protected static String provider(String pluginId) {
        return SignInPlugin.providerId(pluginId, "corp");
    }

    /** Members of {@code group} of the provider get a role holding {@code permissions}, everywhere. */
    protected UUID mapGroup(String providerId, String group, String... permissions) {
        RoleEntity role = roles.save(new RoleEntity("sso-" + UUID.randomUUID(), false));
        for (String permission : permissions) {
            rolePermissions.save(new RolePermissionEntity(role.getId(), permission));
        }
        mappings.save(new GroupMappingEntity(providerId, group, role.getId(), "GLOBAL", ScopeIds.GLOBAL));
        return role.getId();
    }

    protected HttpResponse<String> loginVia(Browser browser, String providerId, String username, String password)
            throws Exception {
        return browser.post(
                "/api/v1/auth/login",
                "{\"provider\":\"%s\",\"username\":\"%s\",\"password\":\"%s\"}"
                        .formatted(providerId, username, password));
    }

    /** A browser signed in through the provider; fails the test when the sign-in is not completed. */
    protected Browser signedInVia(String providerId, String username, String password) throws Exception {
        Browser browser = browser();
        HttpResponse<String> response = loginVia(browser, providerId, username, password);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("AUTHENTICATED");
        return browser;
    }

    protected List<String> providerIds() throws Exception {
        HttpResponse<String> response = browser().send("GET", "/api/v1/auth/providers", null);
        return JsonPath.read(response.body(), "$.data[*].id");
    }

    protected AppUserEntity account(String providerId, String subject) {
        return users.findByProviderIdAndExternalSubject(providerId, subject).orElseThrow();
    }
}
