package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginArtifactRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * design.md §2/task 6.8: a plugin bounded to {@code artemis-studio.plugins.start-timeout-seconds} at boot
 * — this class pins it to one second, and its fixture plugin sleeps three in {@code
 * @PostConstruct}, so its {@code active} row is expected to time out into {@code needs_restart}
 * rather than delay the rest of boot. A separate context from {@link PluginHostIT}'s, since the
 * timeout is a singleton {@code PluginProperties} value read once at context start.
 */
@TestPropertySource(properties = "artemis-studio.plugins.start-timeout-seconds=1")
class PluginHostStartTimeoutIT extends PostgresIntegrationTest {

    @Autowired
    PluginHost host;

    @Autowired
    io.github.sudoitir.artemisstudio.kernel.plugin.internal.store.PluginStore store;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    PluginInstallRepository installs;

    @Autowired
    PluginArtifactRepository artifacts;

    @Autowired
    PluginDescriptorParser descriptorParser;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private String seededId;
    private String seededSha;

    @AfterEach
    void cleanUp() {
        if (seededId != null) {
            registry.get(seededId).ifPresent(slot -> {
                if (slot instanceof PluginRuntimeRegistry.Active active) {
                    active.runtime().close();
                }
            });
            registry.remove(seededId);
            installs.deleteById(seededId);
        }
        if (seededSha != null) {
            artifacts.deleteById(seededSha);
        }
    }

    @Test
    void aPluginThatDoesNotStartWithinTheTimeoutIsFailedAndNeedsRestart() throws Exception {
        String id = "acme-slow-" + Long.toString(System.nanoTime(), 36);
        seededId = id;
        String pkg = "com.acme." + id.replace('-', '_');
        PluginJarBuilder builder = new PluginJarBuilder(id)
                .descriptorField("basePackage", pkg)
                .descriptorField("configuration", pkg + ".PluginConfig")
                .withoutDefaultConfiguration()
                .source(pkg + ".PluginConfig", """
                        package %s;
                        import jakarta.annotation.PostConstruct;
                        import org.springframework.context.annotation.ComponentScan;
                        import org.springframework.context.annotation.Configuration;
                        @Configuration
                        @ComponentScan
                        public class PluginConfig {
                            @PostConstruct
                            void slowStart() throws InterruptedException {
                                Thread.sleep(3000);
                            }
                        }
                        """.formatted(pkg))
                .changelog("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <databaseChangeLog
                                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                        </databaseChangeLog>
                        """);

        Path jar = builder.build();
        seededSha = store.put(Files.readAllBytes(jar));
        PluginDescriptor descriptor;
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            descriptor = descriptorParser.parse(
                    jarFile.getInputStream(jarFile.getEntry("META-INF/artemis-studio/plugin.json"))
                            .readAllBytes());
        }
        String descriptorJson = json.writeValueAsString(descriptor);

        PluginInstallEntity entity = new PluginInstallEntity(id, "1.0.0", "Acme", seededSha, "tester", descriptorJson);
        entity.transitionTo(PluginInstallStatus.ACTIVE);
        installs.save(entity);

        jdbc.update("DELETE FROM studio_boot");
        host.runStartupSequence();

        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)
                && host.status(id)
                        .map(s -> s.status() != PluginInstallStatus.NEEDS_RESTART)
                        .orElse(true)) {
            Thread.sleep(50);
        }

        assertThat(host.status(id)).get().satisfies(s -> {
            assertThat(s.status()).isEqualTo(PluginInstallStatus.NEEDS_RESTART);
            assertThat(s.failure()).contains("did not start within 1");
        });
    }
}
