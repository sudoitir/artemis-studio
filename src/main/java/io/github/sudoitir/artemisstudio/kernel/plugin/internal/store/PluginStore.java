package io.github.sudoitir.artemisstudio.kernel.plugin.internal.store;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginArtifactEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginArtifactRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The content-addressed jar store and the {@code plugin_install} state machine (task 5.6,
 * design.md §4). Every jar this store holds has already passed {@link
 * io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.PluginValidator}.
 *
 * <p>ponytail: a plugin jar is capped at 50&nbsp;MB (PluginValidator's own limit), so reading and
 * writing it as one {@code byte[]} is simple and safe; the JDBC {@code getBinaryStream} path this
 * store's javadoc used to promise is only worth the extra plumbing if that cap ever moves into
 * the hundreds of megabytes.
 */
@Service
@RequiredArgsConstructor
public class PluginStore {

    private static final Path TEMP_DIR = Path.of(System.getProperty("java.io.tmpdir"), "artemis-plugins");

    private final PluginArtifactRepository artifacts;
    private final PluginInstallRepository installs;

    /** Stores {@code content}, keyed by its own sha256. A second upload of the same bytes is a no-op. */
    @Transactional
    public String put(byte[] content) {
        String sha256 = sha256Hex(content);
        if (!artifacts.existsById(sha256)) {
            artifacts.save(new PluginArtifactEntity(sha256, content));
        }
        return sha256;
    }

    /**
     * Writes the stored artifact to a private (mode 0600) temp file, re-checking its bytes
     * against {@code sha256} first — a corrupt or tampered blob is never handed to a jar reader.
     */
    @Transactional(readOnly = true)
    public Path materialize(String sha256) throws IOException {
        PluginArtifactEntity artifact = artifacts
                .findById(sha256)
                .orElseThrow(() -> new PluginStoreException("No stored artifact for sha256 " + sha256));
        byte[] content = artifact.getContent();
        String actual = sha256Hex(content);
        if (!actual.equals(sha256)) {
            throw new PluginStoreException(
                    "The stored artifact for %s does not match its own checksum (recomputed %s) — it is corrupt."
                            .formatted(sha256, actual));
        }
        Files.createDirectories(TEMP_DIR);
        Path file = TEMP_DIR.resolve(sha256 + ".jar");
        Files.deleteIfExists(file);
        createOwnerOnly(file);
        Files.write(file, content);
        return file;
    }

    /**
     * Removes every artifact not referenced as a plugin's current or previous version. Returns
     * how many were removed.
     */
    @Transactional
    public int garbageCollect() {
        var unreferenced = artifacts.findUnreferencedSha256();
        if (unreferenced.isEmpty()) {
            return 0;
        }
        artifacts.deleteAllById(unreferenced);
        return unreferenced.size();
    }

    /** Starts a fresh install, in {@code activating} status, for a plugin id with no existing row. */
    @Transactional
    public PluginInstallEntity beginInstall(
            PluginDescriptor descriptor, String sha256, String descriptorJson, String installedBy) {
        var entity = new PluginInstallEntity(
                descriptor.id(), descriptor.version(), descriptor.vendor().name(), sha256, installedBy, descriptorJson);
        return installs.save(entity);
    }

    /** Starts an update of an already-installed plugin: the current version becomes {@code previous}. */
    @Transactional
    public PluginInstallEntity beginUpdate(
            String id, String version, String sha256, String descriptorJson, boolean schemaChanged) {
        PluginInstallEntity entity =
                installs.findById(id).orElseThrow(() -> new PluginStoreException("No installed plugin " + id));
        entity.update(version, sha256, descriptorJson, schemaChanged);
        return entity;
    }

    @Transactional
    public void transitionTo(String id, PluginInstallStatus status) {
        installs.findById(id).ifPresentOrElse(entity -> entity.transitionTo(status), () -> {
            throw new PluginStoreException("No installed plugin " + id);
        });
    }

    @Transactional
    public void fail(String id, String reason) {
        installs.findById(id).ifPresentOrElse(entity -> entity.fail(reason), () -> {
            throw new PluginStoreException("No installed plugin " + id);
        });
    }

    public Optional<PluginInstallEntity> find(String id) {
        return installs.findById(id);
    }

    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Creates {@code file} with owner-only permissions already in force, so the jar's bytes are
     * never briefly readable under the process umask before being locked down.
     */
    private static void createOwnerOnly(Path file) throws IOException {
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(
                    file,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(file);
            File f = file.toFile();
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setReadable(true, true);
            f.setWritable(true, true);
        }
    }
}
