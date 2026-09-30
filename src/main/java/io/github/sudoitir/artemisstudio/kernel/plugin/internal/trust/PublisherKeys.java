package io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Signer;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/** Parses a publisher key the way its owner publishes it: a PEM certificate or a PEM public key. */
public final class PublisherKeys {

    private static final String CERTIFICATE = "CERTIFICATE";
    private static final String PUBLIC_KEY = "PUBLIC KEY";
    private static final List<String> KEY_ALGORITHMS = List.of("EC", "RSA", "DSA", "Ed25519");

    private PublisherKeys() {}

    /**
     * A {@code CERTIFICATE} block gives the signer with the certificate's subject; a {@code PUBLIC KEY}
     * block gives it with an empty subject.
     *
     * @throws IllegalArgumentException with a readable message for anything else
     */
    public static Signer parse(String pem) {
        String text = pem == null ? "" : pem.strip();
        if (text.startsWith(begin(CERTIFICATE))) {
            return certificate(text);
        }
        if (text.startsWith(begin(PUBLIC_KEY))) {
            return publicKey(text);
        }
        throw new IllegalArgumentException(
                "Expected a PEM certificate (-----BEGIN CERTIFICATE-----) or public key (-----BEGIN PUBLIC KEY-----)");
    }

    private static Signer certificate(String pem) {
        try {
            var factory = CertificateFactory.getInstance("X.509");
            var certificate = (X509Certificate)
                    factory.generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            return Signer.of(certificate);
        } catch (CertificateException e) {
            throw new IllegalArgumentException("The certificate could not be read: " + e.getMessage(), e);
        }
    }

    private static Signer publicKey(String pem) {
        byte[] der;
        try {
            der = Base64.getMimeDecoder()
                    .decode(pem.replace(begin(PUBLIC_KEY), "").replace("-----END " + PUBLIC_KEY + "-----", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The public key is not valid base64", e);
        }
        for (String algorithm : KEY_ALGORITHMS) {
            try {
                KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(der));
                return new Signer(Signer.fingerprint(der), "", der);
            } catch (java.security.GeneralSecurityException ignored) {
                // try the next algorithm
            }
        }
        throw new IllegalArgumentException("The public key is not an EC, RSA, DSA or Ed25519 key");
    }

    private static String begin(String label) {
        return "-----BEGIN " + label + "-----";
    }
}
