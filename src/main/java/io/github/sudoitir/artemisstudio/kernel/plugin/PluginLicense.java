package io.github.sudoitir.artemisstudio.kernel.plugin;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * A plugin's own license (ADR-0153): the file an administrator uploaded for it, and a way to say
 * whether the plugin accepts it. Inject it into a plugin bean; Studio puts one bound to the plugin
 * into its context, so no method takes a plugin id and one plugin can neither read nor judge
 * another's license.
 *
 * <p>Studio stores the file as opaque bytes and never interprets it. The plugin reads it, decides
 * what it means, and reports a {@link Verdict} for it; Studio shows that verdict to administrators
 * and in the operational health. A plugin declares that it needs a license with
 * {@code "requiresLicense": true} in its descriptor, and is told when the file changes with a
 * {@link PluginLicenseChanged} event.
 *
 * <p>The content of a license is never logged or audited by Studio, and a plugin should keep it
 * that way.
 */
@PluginApi
public final class PluginLicense {

    /** What a plugin may tell Studio about the file it was given. */
    @PluginApi
    public enum Status {
        VALID,
        EXPIRED,
        OVER_LIMIT,
        INVALID
    }

    /**
     * The stored file.
     *
     * @param content the bytes as uploaded, at most 64 KiB
     * @param sha256 the lowercase hex SHA-256 of {@code content}, to quote back in {@link #report}
     * @param uploadedAt when the file was uploaded
     */
    @PluginApi
    public record LicenseFile(byte[] content, String sha256, Instant uploadedAt) {

        public LicenseFile {
            Objects.requireNonNull(content, "content");
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LicenseFile f
                    && Arrays.equals(content, f.content)
                    && sha256.equals(f.sha256)
                    && uploadedAt.equals(f.uploadedAt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sha256, uploadedAt);
        }

        /** Never the content. */
        @Override
        public String toString() {
            return "LicenseFile[sha256=" + sha256 + ", size=" + content.length + ", uploadedAt=" + uploadedAt + "]";
        }
    }

    /**
     * The plugin's judgement of a file, shown to administrators.
     *
     * @param status whether the file is accepted
     * @param expiresAt when it stops being valid, or {@code null} when it does not expire or the plugin cannot tell
     * @param licensee who it was issued to, shown as is; at most 200 characters, longer is cut
     * @param detail a short sentence for administrators, such as why a file is invalid; at most 500 characters,
     *     longer is cut. Never put the file's content in it
     */
    @PluginApi
    public record Verdict(Status status, Instant expiresAt, String licensee, String detail) {

        public Verdict {
            Objects.requireNonNull(status, "status");
        }
    }

    private final PluginLicenseStore store;
    private final String pluginId;

    PluginLicense(PluginLicenseStore store, String pluginId) {
        this.store = store;
        this.pluginId = pluginId;
    }

    /** The file an administrator uploaded for this plugin, or empty when there is none. */
    public Optional<LicenseFile> file() {
        return store.file(pluginId);
    }

    /**
     * Says what the plugin made of a file. Studio ignores the report when {@code sha256} is not the
     * hash of the file it holds now, for example because an administrator replaced it in the meantime,
     * so a late report cannot mark a new file valid. Reporting again for the same file replaces the
     * previous verdict.
     */
    public void report(String sha256, Verdict verdict) {
        store.report(pluginId, sha256, verdict);
    }
}
