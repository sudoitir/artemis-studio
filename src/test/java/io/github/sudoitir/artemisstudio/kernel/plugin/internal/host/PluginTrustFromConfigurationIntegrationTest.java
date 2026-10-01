package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.artemisstudio.ArtemisStudioApplication;
import io.github.sudoitir.artemisstudio.kernel.core.StudioHealth;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginArtifactRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry.Active;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.store.PluginStore;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.KeySource;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.PluginTrust;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.PluginTrustHealthIndicator;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Signer;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.TestSigningKeys;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Trusted publisher keys from {@code artemis-studio.plugins.trusted-keys} (ADR-0166), through a real second
 * start of Studio on the shared database: the keys are reconciled before the plugin host starts the
 * installed plugins, and a key dropped from the configuration stops being trusted at the next start.
 */
class PluginTrustFromConfigurationIntegrationTest extends PostgresIntegrationTest {

    private static final TestSigningKeys.Key PUBLISHER = TestSigningKeys.PUBLISHER;
    private static final Duration BOOT = Duration.ofSeconds(60);

    @Autowired
    PluginHost host;

    @Autowired
    PluginStore store;

    @Autowired
    PluginTrust trust;

    @Autowired
    PluginTrustHealthIndicator health;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    PluginInstallRepository installs;

    @Autowired
    PluginArtifactRepository artifacts;

    @Autowired
    JdbcTemplate jdbc;

    private String pluginId;
    private String sha;
    private ConfigurableApplicationContext started;

    @AfterEach
    void cleanUp() {
        if (started != null) {
            started.close();
        }
        if (pluginId != null) {
            registry.get(pluginId).ifPresent(slot -> {
                if (slot instanceof Active active) {
                    active.runtime().close();
                }
            });
            registry.remove(pluginId);
            installs.deleteById(pluginId);
        }
        if (sha != null) {
            artifacts.deleteById(sha);
        }
        jdbc.update("DELETE FROM plugin_trusted_key");
    }

    /** A certificate in PEM form: the made-up "Example Publisher" certificate the test jars are signed with. */
    private static String certificatePem() throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes())
                        .encodeToString(PUBLISHER.certificate().getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    /** Studio started again on the same database, as an operator restarts it with another configuration. */
    private ConfigurableApplicationContext restart(String... configuration) {
        List<String> properties = new ArrayList<>(connectionProperties());
        properties.add("server.port=0");
        properties.addAll(List.of(configuration));
        return new SpringApplicationBuilder(ArtemisStudioApplication.class)
                .run(properties.stream().map(property -> "--" + property).toArray(String[]::new));
    }

    /** An installed plugin signed by the publisher whose version waits for a restart, so the next boot starts it. */
    private void aPluginSignedByThePublisherWaitsForARestart() throws Exception {
        pluginId = "example-config-" + Long.toString(System.nanoTime(), 36);
        String pkg = "com.example." + pluginId.replace('-', '_');
        sha = store.put(Files.readAllBytes(new PluginJarBuilder(pluginId)
                .descriptorField("basePackage", pkg)
                .descriptorField("configuration", pkg + ".PluginConfig")
                .descriptorField("activation", "RESTART")
                .build()));
        trust.pin("Example Publisher", Signer.of(PUBLISHER.certificate()), "test");
        host.activate(sha, "tester", false);
        await("the plugin waits for a restart")
                .atMost(Duration.ofSeconds(30))
                .until(() -> installs.findById(pluginId)
                        .filter(e -> e.status() == PluginInstallStatus.NEEDS_RESTART)
                        .isPresent());
    }

    @Test
    void aPluginSignedByAConfiguredKeyStartsTrustedOnTheFirstBoot() throws Exception {
        aPluginSignedByThePublisherWaitsForARestart();
        // The first boot with this configuration: the table has never heard of the key.
        jdbc.update("DELETE FROM plugin_trusted_key");
        assertThat(host.status(pluginId).orElseThrow().verified()).isFalse();

        started = restart(
                "artemis-studio.plugins.trusted-keys[0].name=Example Publisher",
                "artemis-studio.plugins.trusted-keys[0].pem=" + certificatePem());

        // Without the reconciliation first, the boot refuses the version as plugin-untrusted (see
        // PluginHostIntegrationTest#theNextBootRefusesAVersionWhoseKeyWasRemovedWhileItWaitedForTheRestart).
        await("the plugin starts at boot")
                .atMost(BOOT)
                .until(() -> installs.findById(pluginId)
                        .filter(e -> e.status() == PluginInstallStatus.ACTIVE)
                        .isPresent());
        assertThat(host.status(pluginId).orElseThrow().verified()).isTrue();
        assertThat(trust.keys()).singleElement().satisfies(key -> {
            assertThat(key.name()).isEqualTo("Example Publisher");
            assertThat(key.source()).isEqualTo(KeySource.CONFIGURATION);
            assertThat(key.fingerprint()).isEqualTo(PUBLISHER.fingerprint());
        });
        var audit = jdbc.queryForMap(
                "SELECT username, outcome, params::text AS params FROM audit_event WHERE action = 'PLUGIN_KEY_ADD'"
                        + " AND target_name = 'Example Publisher' ORDER BY id DESC LIMIT 1");
        assertThat(audit)
                .containsEntry("username", "configuration")
                .containsEntry("outcome", "SUCCESS")
                .extractingByKey("params")
                .asString()
                .contains(PUBLISHER.fingerprint());
    }

    @Test
    void removingTheKeyFromTheConfigurationUntrustsItAtTheNextStart() throws Exception {
        pluginId = "example-config-" + Long.toString(System.nanoTime(), 36);
        String pkg = "com.example." + pluginId.replace('-', '_');
        sha = store.put(Files.readAllBytes(new PluginJarBuilder(pluginId)
                .descriptorField("basePackage", pkg)
                .descriptorField("configuration", pkg + ".PluginConfig")
                .build()));
        trust.pin("Example Publisher", Signer.of(PUBLISHER.certificate()), "configuration");
        host.activate(sha, "tester", false);
        await("the plugin is active")
                .atMost(Duration.ofSeconds(30))
                .until(() -> installs.findById(pluginId)
                        .filter(e -> e.status() == PluginInstallStatus.ACTIVE)
                        .isPresent());
        assertThat(host.status(pluginId).orElseThrow().verified()).isTrue();

        started = restart();

        assertThat(trust.keys()).isEmpty();
        var summary = host.status(pluginId).orElseThrow();
        assertThat(summary.status()).isEqualTo(PluginInstallStatus.ACTIVE);
        assertThat(summary.verified()).isFalse();
        var report = health.health();
        assertThat(report.getStatus()).isEqualTo(StudioHealth.DEGRADED);
        assertThat(report.getDetails().get("unverified")).asList().contains(pluginId);
        var audit = jdbc.queryForMap(
                "SELECT username, outcome, params::text AS params FROM audit_event WHERE action = 'PLUGIN_KEY_REMOVE'"
                        + " AND target_name = ? ORDER BY id DESC LIMIT 1",
                PUBLISHER.fingerprint());
        assertThat(audit).containsEntry("username", "configuration").containsEntry("outcome", "SUCCESS");
    }

    @Test
    void anInvalidEntryFailsStartupNamingItsIndex() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> started = restart(
                        "artemis-studio.plugins.trusted-keys[0].name=Example Publisher",
                        "artemis-studio.plugins.trusted-keys[0].pem=not a key"))
                .hasStackTraceContaining("artemis-studio.plugins.trusted-keys[0]")
                .hasStackTraceContaining("Expected a PEM certificate");
    }
}
