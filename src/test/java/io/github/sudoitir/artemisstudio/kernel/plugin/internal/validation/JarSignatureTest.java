package io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginProperties;
import io.github.sudoitir.artemisstudio.kernel.plugin.StudioVersion;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.TestSigningKeys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

/** The signature checks of {@link PluginValidator}: the signer is reported, and every tampering is refused. */
class JarSignatureTest {

    private static final String CLASS_ENTRY = "com/acme/acme_notes/PluginConfig.class";

    private static ValidationReport validate(Path jar) {
        ObjectProvider<BuildProperties> noBuildInfo = mock(ObjectProvider.class);
        when(noBuildInfo.getIfAvailable()).thenReturn(null);
        var studioVersion =
                new StudioVersion(noBuildInfo, new PluginProperties("2026.03.15", false, null, null, null, null, null));
        return new PluginValidator(new PluginDescriptorParser(), studioVersion).validate(jar, Set.of());
    }

    private static PluginJarBuilder plugin() {
        return new PluginJarBuilder("acme-notes");
    }

    private static Set<String> codes(ValidationReport report) {
        return Set.copyOf(report.errors().stream().map(Violation::code).toList());
    }

    /** Copies {@code jar}, letting {@code edit} change or drop (return null) each entry, then appends {@code extraName}. */
    private static Path rewrite(Path jar, BiFunction<String, byte[], byte[]> edit, String extraName) throws Exception {
        Path out = Files.createTempFile(jar.getParent(), "tampered", ".jar");
        try (var in = new ZipInputStream(Files.newInputStream(jar));
                ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] edited = entry.isDirectory() ? new byte[0] : edit.apply(entry.getName(), in.readAllBytes());
                if (edited != null) {
                    zip.putNextEntry(new ZipEntry(entry.getName()));
                    zip.write(edited);
                    zip.closeEntry();
                }
            }
            if (extraName != null) {
                zip.putNextEntry(new ZipEntry(extraName));
                zip.write("x".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out;
    }

    private static BiFunction<String, byte[], byte[]> change(String name, UnaryOperator<byte[]> f) {
        return (n, content) -> n.equals(name) ? f.apply(content) : content;
    }

    @Test
    void signedJarReportsItsSigner() throws Exception {
        ValidationReport report = validate(plugin().build());
        assertThat(report.errors()).isEmpty();
        assertThat(report.signer().fingerprint()).isEqualTo(TestSigningKeys.PUBLISHER.fingerprint());
        assertThat(report.signer().subject()).isEqualTo("CN=Acme Test Publisher");
    }

    @Test
    void unsignedJarIsValidWithoutASigner() throws Exception {
        ValidationReport report = validate(plugin().unsigned().build());
        assertThat(report.errors()).isEmpty();
        assertThat(report.signer()).isNull();
    }

    @Test
    void aFlippedClassByteIsRefused() throws Exception {
        Path jar = rewrite(
                plugin().build(),
                change(CLASS_ENTRY, b -> {
                    byte[] copy = b.clone();
                    copy[copy.length - 1] ^= 1;
                    return copy;
                }),
                null);
        assertThat(codes(validate(jar))).containsExactly("jar-signature-invalid");
    }

    @Test
    void anEntryAppendedAfterSigningIsRefused() throws Exception {
        for (String extra : new String[] {"com/acme/acme_notes/Extra.class", "META-INF/artemis-studio/ui/x.js"}) {
            Path jar = rewrite(plugin().build(), (n, content) -> content, extra);
            ValidationReport report = validate(jar);
            assertThat(codes(report)).as(extra).containsExactly("jar-entry-unsigned");
            assertThat(report.signer()).isNull();
        }
    }

    @Test
    void anEntryDeletedAfterSigningIsRefused() throws Exception {
        Path jar = rewrite(plugin().build(), change(CLASS_ENTRY, b -> null), null);
        assertThat(codes(validate(jar))).containsExactly("jar-entry-missing");
    }

    @Test
    void aJarSignedByTwoKeysIsRefused() throws Exception {
        Path signed = plugin().build();
        Path resigned = PluginJarBuilder.sign(signed, TestSigningKeys.OTHER, "OTHER");
        assertThat(codes(validate(resigned))).containsExactly("jar-signers-mixed");
    }

    @Test
    void aChangedManifestIsRefused() throws Exception {
        Path jar = rewrite(
                plugin().build(),
                change("META-INF/MANIFEST.MF", b -> {
                    String manifest = new String(b, StandardCharsets.UTF_8);
                    return manifest.replace("Manifest-Version: 1.0", "Manifest-Version: 1.0\r\nCreated-By: tamper")
                            .getBytes(StandardCharsets.UTF_8);
                }),
                null);
        assertThat(codes(validate(jar))).containsExactly("jar-signature-invalid");
    }
}
