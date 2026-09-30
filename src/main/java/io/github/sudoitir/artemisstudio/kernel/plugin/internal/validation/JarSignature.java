package io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.CodeSigner;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

/**
 * Verifies a standard {@code jarsigner} signature (design.md §1). The JDK checks each entry's
 * digest against the manifest and the manifest against the signature file; it does not notice an
 * entry that is unsigned or missing, so both are checked here. An unsigned jar is not an error:
 * {@link #check} returns {@code null} and the trust decision handles it.
 */
final class JarSignature {

    /** {@code META-INF/<NAME>.SF} and its {@code .RSA}, {@code .EC} or {@code .DSA} block. */
    static final Pattern METADATA = Pattern.compile("META-INF/[^/]+\\.(SF|RSA|EC|DSA)");

    private static final String MANIFEST = "META-INF/MANIFEST.MF";

    private JarSignature() {}

    /**
     * @param jar opened with {@code verify=true}
     * @return the one signer of every entry, or {@code null} for an unsigned jar or after a
     *     violation was recorded
     */
    static Signer check(JarFile jar, List<String> entryNames, List<Violation> violations) throws IOException {
        List<JarEntry> entries = Collections.list(jar.entries());
        try {
            // Reading an entry to its end is what makes JarFile verify its digest.
            for (JarEntry entry : entries) {
                try (InputStream in = jar.getInputStream(entry)) {
                    in.transferTo(OutputStream.nullOutputStream());
                }
            }
        } catch (SecurityException e) {
            violations.add(new Violation(
                    "jar-signature-invalid",
                    "The jar's signature does not match its contents: " + e.getMessage(),
                    "Rebuild and sign the jar again; do not change it after signing."));
            return null;
        }

        long signatureFiles = entryNames.stream()
                .filter(n -> n.matches("META-INF/[^/]+\\.SF"))
                .count();
        if (signatureFiles > 1) {
            violations.add(mixed("The jar carries more than one signature."));
            return null;
        }
        boolean anySigned = entries.stream().anyMatch(e -> e.getCodeSigners() != null);
        if (signatureFiles == 0 && !anySigned) {
            return null;
        }

        Certificate signer = null;
        for (JarEntry entry : entries) {
            String name = entry.getName();
            if (entry.isDirectory()
                    || name.equals(MANIFEST)
                    || METADATA.matcher(name).matches()) {
                continue;
            }
            CodeSigner[] signers = entry.getCodeSigners();
            if (signers == null || signers.length == 0) {
                violations.add(new Violation(
                        "jar-entry-unsigned",
                        "\"%s\" is not covered by the jar's signature.".formatted(name),
                        "Rebuild and sign the jar again; do not add or replace files after signing."));
                return null;
            }
            Certificate certificate =
                    signers[0].getSignerCertPath().getCertificates().get(0);
            if (signers.length != 1 || (signer != null && !signer.equals(certificate))) {
                violations.add(mixed("\"%s\" is signed by a different key than the rest of the jar.".formatted(name)));
                return null;
            }
            signer = certificate;
        }

        Manifest manifest = jar.getManifest();
        Set<String> names = new HashSet<>(entryNames);
        if (manifest != null) {
            for (String listed : manifest.getEntries().keySet()) {
                if (!names.contains(listed)) {
                    violations.add(new Violation(
                            "jar-entry-missing",
                            "\"%s\" is listed in the signed manifest but is not in the jar.".formatted(listed),
                            "Rebuild and sign the jar again; do not remove files after signing."));
                    return null;
                }
            }
        }
        return signer == null ? null : Signer.of((X509Certificate) signer);
    }

    private static Violation mixed(String message) {
        return new Violation("jar-signers-mixed", message, "Sign the whole jar once, with one key.");
    }
}
