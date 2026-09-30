package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.artemisstudio.ArtemisStudioApplication;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry.Active;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.store.PluginStore;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.TrustedTestKey;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two replicas, this context (A) and a second one on the same database (B): a plugin installed,
 * disabled, enabled, uninstalled and purged on A follows on B, which runs it only while its row says
 * active, and neither replica redoes what it already has.
 */
class PluginReplicaReconcileTest extends PostgresIntegrationTest {

    private static final Duration FOLLOWS = Duration.ofSeconds(30);

    private static ConfigurableApplicationContext other;

    @Autowired
    PluginHost hostA;

    @Autowired
    PluginStore store;

    @Autowired
    PluginRuntimeRegistry registryA;

    @Autowired
    PluginInstallRepository installs;

    @Autowired
    FeatureRegistry featuresA;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void startTheOtherReplica() {
        List<String> properties = new ArrayList<>(connectionProperties());
        properties.add("server.port=0");
        other = new SpringApplicationBuilder(ArtemisStudioApplication.class)
                .run(properties.stream().map(property -> "--" + property).toArray(String[]::new));
    }

    @AfterAll
    static void stopTheOtherReplica() {
        other.close();
    }

    @Test
    void aPluginFollowsItsRowFromOneReplicaToTheOther() throws Exception {
        TrustedTestKey.trust(jdbc);
        PluginRuntimeRegistry registryB = other.getBean(PluginRuntimeRegistry.class);
        FeatureRegistry featuresB = other.getBean(FeatureRegistry.class);
        String id = "acme-replica-" + Long.toString(System.nanoTime(), 36);
        String pkg = "com.acme." + id.replace('-', '_');
        String sha = store.put(Files.readAllBytes(new PluginJarBuilder(id)
                .descriptorField("basePackage", pkg)
                .descriptorField("configuration", pkg + ".PluginConfig")
                .build()));
        try {
            hostA.activate(sha, "tester", false);
            awaitActive(registryA, id);
            awaitActive(registryB, id);
            assertThat(featuresB.manifestVersion()).isEqualTo(featuresA.manifestVersion());

            Active onA = (Active) registryA.get(id).orElseThrow();
            Active onB = (Active) registryB.get(id).orElseThrow();
            other.getBean(PluginHost.class).reconcileRuntimes();
            hostA.reconcileRuntimes();
            assertThat(registryA.get(id)).containsSame(onA);
            assertThat(registryB.get(id)).containsSame(onB);

            hostA.disable(id, false, "tester");
            awaitGone(registryB, id);
            assertThat(featuresB.manifestVersion()).isEqualTo(featuresA.manifestVersion());

            hostA.enable(id, "tester", false);
            awaitActive(registryB, id);

            hostA.disable(id, false, "tester");
            hostA.uninstall(id, false, "tester");
            awaitGone(registryB, id);
            hostA.purge(id, "tester");
            assertThat(installs.findById(id)).isEmpty();
            assertThat(registryB.get(id)).isEmpty();
        } finally {
            for (PluginRuntimeRegistry registry : List.of(registryA, registryB)) {
                registry.get(id).ifPresent(slot -> {
                    if (slot instanceof Active active) {
                        active.runtime().close();
                    }
                });
                registry.remove(id);
            }
            installs.deleteById(id);
            jdbc.update("DELETE FROM plugin_artifact WHERE sha256 = ?", sha);
        }
    }

    @Test
    void aReplicaLeavesAPluginThatNeedsARestartAsItIs() throws Exception {
        PluginHost hostB = other.getBean(PluginHost.class);
        PluginRuntimeRegistry registryB = other.getBean(PluginRuntimeRegistry.class);
        String id = "acme-restart-" + Long.toString(System.nanoTime(), 36);
        String pkg = "com.acme." + id.replace('-', '_');
        String sha = store.put(Files.readAllBytes(new PluginJarBuilder(id)
                .descriptorField("basePackage", pkg)
                .descriptorField("configuration", pkg + ".PluginConfig")
                .build()));
        try {
            hostA.activate(sha, "tester", false);
            awaitActive(registryB, id);
            Active running = (Active) registryB.get(id).orElseThrow();

            store.update(id, row -> row.needsRestart("Restart Studio."));
            hostB.reconcileRuntimes();

            assertThat(installs.findById(id))
                    .hasValueSatisfying(row -> assertThat(row.status()).isEqualTo(PluginInstallStatus.NEEDS_RESTART));
            assertThat(registryB.get(id)).containsSame(running);
        } finally {
            for (PluginRuntimeRegistry registry : List.of(registryA, registryB)) {
                registry.get(id).ifPresent(slot -> {
                    if (slot instanceof Active active) {
                        active.runtime().close();
                    }
                });
                registry.remove(id);
            }
            installs.deleteById(id);
            jdbc.update("DELETE FROM plugin_artifact WHERE sha256 = ?", sha);
        }
    }

    private static void awaitActive(PluginRuntimeRegistry registry, String id) {
        await("plugin '" + id + "' runs")
                .atMost(FOLLOWS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> registry.get(id).filter(Active.class::isInstance).isPresent());
    }

    private static void awaitGone(PluginRuntimeRegistry registry, String id) {
        Supplier<Boolean> gone = () -> registry.get(id).isEmpty();
        await("plugin '" + id + "' stops")
                .atMost(FOLLOWS)
                .pollInterval(Duration.ofMillis(100))
                .until(gone::get);
    }
}
