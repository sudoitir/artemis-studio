package io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.kernel.plugin.support.TestSigningKeys;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PublisherKeysTest {

    private static final Base64.Encoder PEM = Base64.getMimeEncoder(64, "\n".getBytes());

    @Test
    void certificateAndPublicKeyGiveTheSameFingerprintAsTheSigner() throws Exception {
        var certificate = TestSigningKeys.PUBLISHER.certificate();
        String certPem = pem("CERTIFICATE", certificate.getEncoded());
        String keyPem = pem("PUBLIC KEY", certificate.getPublicKey().getEncoded());

        var fromCertificate = PublisherKeys.parse(certPem);
        var fromKey = PublisherKeys.parse(keyPem);

        assertThat(fromCertificate.fingerprint()).isEqualTo(TestSigningKeys.PUBLISHER.fingerprint());
        assertThat(fromKey.fingerprint()).isEqualTo(TestSigningKeys.PUBLISHER.fingerprint());
        assertThat(fromCertificate.subject()).contains("Acme Test Publisher");
        assertThat(fromKey.subject()).isEmpty();
        assertThat(fromKey.publicKey()).isEqualTo(certificate.getPublicKey().getEncoded());
    }

    @Test
    void garbageIsRejectedWithAReadableMessage() {
        assertThatThrownBy(() -> PublisherKeys.parse("not a key")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PublisherKeys.parse(null)).isInstanceOf(IllegalArgumentException.class);
        String badCertificate = pem("CERTIFICATE", new byte[] {1, 2, 3});
        String badKey = pem("PUBLIC KEY", new byte[] {1, 2, 3});
        assertThatThrownBy(() -> PublisherKeys.parse(badCertificate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("certificate");
        assertThatThrownBy(() -> PublisherKeys.parse(badKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public key");
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + PEM.encodeToString(der) + "\n-----END " + label + "-----\n";
    }
}
