package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginArtifactRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.store.PluginStore;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * design.md §5's "Studio upgraded outside {@code since..until}": at boot, an {@code active} row
 * whose stored descriptor no longer matches the running Studio version becomes {@code
 * incompatible} rather than being started (task 6.8). Its own {@code @TestPropertySource} pins
 * {@code artemis-studio.plugins.studio-version-override} to a version older than every fixture
 * plugin's {@code studio.since} — a separate Spring context from {@link PluginHostIT}'s, because
 * the override is read once into the singleton {@code StudioVersion} bean at context start.
 */
@TestPropertySource(properties = "artemis-studio.plugins.studio-version-override=2020.01.0")
class PluginHostIncompatibleIT extends PostgresIntegrationTest {

    @Autowired
    PluginHost host;

    @Autowired
    PluginStore store;

    @Autowired
    PluginInstallRepository installs;

    @Autowired
    PluginArtifactRepository artifacts;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private String seededId;
    private String seededSha;

    @AfterEach
    void cleanUp() {
        if (seededId != null) {
            installs.deleteById(seededId);
        }
        if (seededSha != null) {
            artifacts.deleteById(seededSha);
        }
    }

    @Test
    void activeRowOutsideTheOverriddenStudioVersionBecomesIncompatibleAtBoot() throws Exception {
        String id = "acme-incompatible-" + Long.toString(System.nanoTime(), 36);
        seededId = id;
        // PluginJarBuilder.defaultDescriptor's studio.since (2026.01.0) is newer than this
        // context's overridden running version (2020.01.0) — the running Studio is too old.
        String descriptorJson = json.writeValueAsString(PluginJarBuilder.defaultDescriptor(id));
        seededSha = store.put(("not-a-real-jar-" + id).getBytes());
        PluginInstallEntity entity = new PluginInstallEntity(id, "1.0.0", "Acme", seededSha, "tester", descriptorJson);
        entity.transitionTo(PluginInstallStatus.ACTIVE);
        installs.save(entity);

        jdbc.update("DELETE FROM studio_boot");
        host.runStartupSequence();

        assertThat(host.status(id)).get().satisfies(s -> {
            assertThat(s.status()).isEqualTo(PluginInstallStatus.INCOMPATIBLE);
            assertThat(s.failure()).contains("2026.01.0");
        });
    }
}
