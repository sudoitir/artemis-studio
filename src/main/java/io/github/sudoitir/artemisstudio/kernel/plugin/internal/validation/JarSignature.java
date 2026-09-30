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
    private static final String REBUILD_ADDED =
            "Rebuild and sign the jar again; do not add or replace files after signing.";

    private JarSignature() {}

    /**
     * @param jar opened with {@code verify=true}
     * @return the one signer of every entry, or {@code null} for an unsigned jar or after a
     *     violation was recorded
     */
    static Signer check(JarFile jar, List<String> entryNames, List<Violation> violations) throws IOException {
        List<JarEntry> entries = Collections.list(jar.entries());
        if (!digestsMatch(jar, entries, violations)) {
            return null;
        }
        long signatureFiles = entryNames.stream()
                .filter(n -> n.matches("META-INF/[^/]+\\.SF"))
                .count();
        if (signatureFiles > 1) {
            violations.add(mixed("The jar carries more than one signature."));
            return null;
        }
        if (signatureFiles == 0 && entries.stream().allMatch(e -> e.getCodeSigners() == null)) {
            return null;
        }
        Certificate[] signer = new Certificate[1];
        for (JarEntry entry : entries) {
            if (!entryCovered(entry, signer, violations)) {
                return null;
            }
        }
        if (!manifestListsOnlyPresent(jar, entryNames, violations)) {
            return null;
        }
        return signer[0] == null ? null : Signer.of((X509Certificate) signer[0]);
    }

    /** Reading an entry to its end is what makes JarFile verify its digest. */
    private static boolean digestsMatch(JarFile jar, List<JarEntry> entries, List<Violation> violations)
            throws IOException {
        try {
            for (JarEntry entry : entries) {
                try (InputStream in = jar.getInputStream(entry)) {
                    in.transferTo(OutputStream.nullOutputStream());
                }
            }
            return true;
        } catch (SecurityException e) {
            violations.add(new Violation(
                    "jar-signature-invalid",
                    "The jar's signature does not match its contents: " + e.getMessage(),
                    "Rebuild and sign the jar again; do not change it after signing."));
            return false;
        }
    }

    /**
     * Checks one entry against the signature and records its signer in {@code signer[0]}.
     *
     * @return false after a violation was recorded
     */
    private static boolean entryCovered(JarEntry entry, Certificate[] signer, List<Violation> violations) {
        String name = entry.getName();
        if (entry.isDirectory() && entry.getSize() > 0) {
            // No loader reads it, but nothing should ever serve bytes the signature does not cover.
            violations.add(new Violation(
                    "jar-entry-unsigned", "\"%s\" is a directory entry with content.".formatted(name), REBUILD_ADDED));
            return false;
        }
        if (entry.isDirectory()
                || name.equals(MANIFEST)
                || METADATA.matcher(name).matches()) {
            return true;
        }
        CodeSigner[] signers = entry.getCodeSigners();
        if (signers == null || signers.length == 0) {
            violations.add(new Violation(
                    "jar-entry-unsigned",
                    "\"%s\" is not covered by the jar's signature.".formatted(name),
                    REBUILD_ADDED));
            return false;
        }
        Certificate certificate =
                signers[0].getSignerCertPath().getCertificates().get(0);
        if (signers.length != 1 || (signer[0] != null && !signer[0].equals(certificate))) {
            violations.add(mixed("\"%s\" is signed by a different key than the rest of the jar.".formatted(name)));
            return false;
        }
        signer[0] = certificate;
        return true;
    }

    private static boolean manifestListsOnlyPresent(JarFile jar, List<String> entryNames, List<Violation> violations)
            throws IOException {
        Manifest manifest = jar.getManifest();
        if (manifest == null) {
            return true;
        }
        Set<String> names = new HashSet<>(entryNames);
        for (String listed : manifest.getEntries().keySet()) {
            if (!names.contains(listed)) {
                violations.add(new Violation(
                        "jar-entry-missing",
                        "\"%s\" is listed in the signed manifest but is not in the jar.".formatted(listed),
                        "Rebuild and sign the jar again; do not remove files after signing."));
                return false;
            }
        }
        return true;
    }

    private static Violation mixed(String message) {
        return new Violation("jar-signers-mixed", message, "Sign the whole jar once, with one key.");
    }
}
