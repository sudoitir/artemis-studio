package io.github.sudoitir.artemisstudio.kernel.plugin.internal.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginArtifactEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginArtifactRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link PluginStore} against a real Postgres (task 5.6): put/materialize/sha mismatch/GC. Every
 * {@link PluginStore} method manages its own transaction, so the test calls them directly rather
 * than wrapping them in one of its own.
 */
class PluginStoreIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    PluginStore store;

    @Autowired
    PluginArtifactRepository artifacts;

    @Autowired
    PluginInstallRepository installs;

    @Autowired
    JdbcTemplate jdbc;

    private String installId;

    @AfterEach
    void tearDown() {
        if (installId != null) {
            installs.deleteById(installId);
        }
        jdbc.update("delete from plugin_upload");
        artifacts.deleteAll();
    }

    @Test
    void putIsIdempotentAndContentAddressed() {
        byte[] content = "hello plugin jar".getBytes(StandardCharsets.UTF_8);
        String sha1 = store.put(content);
        String sha2 = store.put(content);

        assertThat(sha1).isEqualTo(sha2).isEqualTo(sha256Hex(content));
        assertThat(artifacts.count()).isEqualTo(1);
    }

    @Test
    void materializeWritesAPrivateFileMatchingTheChecksum() throws Exception {
        byte[] content = "materialize me".getBytes(StandardCharsets.UTF_8);
        String sha = store.put(content);

        var path = store.materialize(sha);
        try {
            assertThat(Files.readAllBytes(path)).isEqualTo(content);
            var perms = Files.getPosixFilePermissions(path);
            assertThat(perms)
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void materializeRejectsACorruptBlob() {
        artifacts.save(new PluginArtifactEntity("deadbeef", "not what the sha says".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> store.materialize("deadbeef"))
                .isInstanceOf(PluginStoreException.class)
                .hasMessageContaining("deadbeef");
    }

    @Test
    void garbageCollectRemovesOnlyUnreferencedArtifacts() {
        byte[] referenced = "kept".getBytes(StandardCharsets.UTF_8);
        byte[] orphan = "removed".getBytes(StandardCharsets.UTF_8);
        String referencedSha = store.put(referenced);
        store.put(orphan);

        installId = "gc-test-" + UUID.randomUUID();
        installs.save(new PluginInstallEntity(installId, "1.0.0", "Acme", referencedSha, "tester", "{}"));

        int removed = store.garbageCollect();

        assertThat(removed).isEqualTo(1);
        assertThat(artifacts.existsById(referencedSha)).isTrue();
    }

    @Test
    void garbageCollectKeepsArtifactsReferencedByAPendingUpload() {
        byte[] pending = "awaiting activation".getBytes(StandardCharsets.UTF_8);
        String pendingSha = store.put(pending);

        jdbc.update(
                "insert into plugin_upload (uploaded_at, sha256, plugin_id, uploaded_by, descriptor, report)"
                        + " values (?, ?, ?, ?, ?::jsonb, ?::jsonb)",
                OffsetDateTime.now(),
                pendingSha,
                "acme-plugin",
                "tester",
                "{}",
                "{}");

        int removed = store.garbageCollect();

        assertThat(removed).isZero();
        assertThat(artifacts.existsById(pendingSha)).isTrue();
    }

    @Test
    void installStateTransitions() {
        installId = "state-test-" + UUID.randomUUID();
        byte[] content = "state machine".getBytes(StandardCharsets.UTF_8);
        String sha = store.put(content);

        installs.save(new PluginInstallEntity(installId, "1.0.0", "Acme", sha, "tester", "{}"));
        assertThat(installs.findById(installId).orElseThrow().status()).isEqualTo(PluginInstallStatus.ACTIVATING);

        store.transitionTo(installId, PluginInstallStatus.ACTIVE);
        PluginInstallEntity active = installs.findById(installId).orElseThrow();
        assertThat(active.status()).isEqualTo(PluginInstallStatus.ACTIVE);
        assertThat(active.getActivatedAt()).isNotNull();

        store.fail(installId, "linkage error in com.acme.Notes");
        PluginInstallEntity failed = installs.findById(installId).orElseThrow();
        assertThat(failed.status()).isEqualTo(PluginInstallStatus.FAILED);
        assertThat(failed.getFailure()).contains("linkage error");
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
