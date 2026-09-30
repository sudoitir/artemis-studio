package io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

/**
 * Who signed a plugin jar. The fingerprint is the SHA-256 of the signer certificate's public key
 * (SubjectPublicKeyInfo DER) as upper-case, colon-separated hex, so it survives a publisher
 * re-issuing the certificate for the same key.
 */
public record Signer(String fingerprint, String subject, byte[] publicKey) {

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
