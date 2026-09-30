package io.github.sudoitir.artemisstudio.kernel.plugin.support;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

/** The two test publisher keys under {@code plugin-signing/}: alias is the file's base name, password {@code changeit}. */
public final class TestSigningKeys {

    public static final String PUBLISHER_RESOURCE = "plugin-signing/publisher.p12";
    public static final String OTHER_RESOURCE = "plugin-signing/other.p12";
    public static final Key PUBLISHER = load(PUBLISHER_RESOURCE);
    public static final Key OTHER = load(OTHER_RESOURCE);

    public record Key(PrivateKey privateKey, X509Certificate certificate, String fingerprint) {}

    private TestSigningKeys() {}

    public static Key load(String resource) {
        String alias = resource.substring(resource.lastIndexOf('/') + 1, resource.lastIndexOf('.'));
        try (InputStream in = TestSigningKeys.class.getClassLoader().getResourceAsStream(resource)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, "changeit".toCharArray());
            X509Certificate certificate = (X509Certificate) store.getCertificate(alias);
            String fingerprint = HexFormat.ofDelimiter(":")
                    .withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(certificate.getPublicKey().getEncoded()));
            return new Key((PrivateKey) store.getKey(alias, "changeit".toCharArray()), certificate, fingerprint);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load test signing key " + resource, e);
        }
    }
}
