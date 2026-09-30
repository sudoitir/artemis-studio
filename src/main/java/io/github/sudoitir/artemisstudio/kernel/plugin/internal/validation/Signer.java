package io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Who signed a plugin jar. The fingerprint is the SHA-256 of the signer certificate's public key
 * (SubjectPublicKeyInfo DER) as upper-case, colon-separated hex, so it survives a publisher
 * re-issuing the certificate for the same key.
 */
public record Signer(String fingerprint, String subject, byte[] publicKey) {

    @Override
    public boolean equals(Object other) {
        return other instanceof Signer(String otherFingerprint, String otherSubject, byte[] otherKey)
                && fingerprint.equals(otherFingerprint)
                && subject.equals(otherSubject)
                && Arrays.equals(publicKey, otherKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fingerprint, subject, Arrays.hashCode(publicKey));
    }

    @Override
    public String toString() {
        return "Signer[fingerprint=" + fingerprint + ", subject=" + subject + ", publicKey="
                + Arrays.toString(publicKey) + "]";
    }

    public static Signer of(X509Certificate certificate) {
        byte[] spki = certificate.getPublicKey().getEncoded();
        return new Signer(
                fingerprint(spki), certificate.getSubjectX500Principal().getName(), spki);
    }

    public static String fingerprint(byte[] spki) {
        try {
            return HexFormat.ofDelimiter(":")
                    .withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(spki));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }
}
