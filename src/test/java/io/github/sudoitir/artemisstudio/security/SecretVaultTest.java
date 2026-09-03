package io.github.sudoitir.artemisstudio.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecretVaultTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(fill((byte) 1));
    private static final String KEY_B = Base64.getEncoder().encodeToString(fill((byte) 2));

    private static byte[] fill(byte b) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, b);
        return k;
    }

    private static SecretVault vault(String key) {
        return new SecretVault(new ArtemisStudioProperties(key, null, null, null, null));
    }

    @Test
    void roundTrips() {
        SecretVault vault = vault(KEY_A);
        UUID cluster = UUID.randomUUID();

        SecretVault.Sealed sealed = vault.encrypt(cluster, "JOLOKIA_BASIC", "s3cr3t");

        assertThat(vault.decrypt(cluster, "JOLOKIA_BASIC", sealed.ciphertext(), sealed.nonce()))
                .isEqualTo("s3cr3t");
    }

    @Test
    void freshNoncePerEncryption() {
        SecretVault vault = vault(KEY_A);
        UUID cluster = UUID.randomUUID();

        SecretVault.Sealed one = vault.encrypt(cluster, "CORE", "same");
        SecretVault.Sealed two = vault.encrypt(cluster, "CORE", "same");

        assertThat(one.nonce()).isNotEqualTo(two.nonce());
        assertThat(one.ciphertext()).isNotEqualTo(two.ciphertext());
    }

    @Test
    void wrongKeyIsRejected() {
        UUID cluster = UUID.randomUUID();
        SecretVault.Sealed sealed = vault(KEY_A).encrypt(cluster, "CORE", "x");

        assertThatThrownBy(() -> vault(KEY_B).decrypt(cluster, "CORE", sealed.ciphertext(), sealed.nonce()))
                .isInstanceOf(SecretVault.SecretDecryptException.class);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        UUID cluster = UUID.randomUUID();
        SecretVault vault = vault(KEY_A);
        SecretVault.Sealed sealed = vault.encrypt(cluster, "CORE", "x");
        sealed.ciphertext()[0] ^= 0x01;

        assertThatThrownBy(() -> vault.decrypt(cluster, "CORE", sealed.ciphertext(), sealed.nonce()))
                .isInstanceOf(SecretVault.SecretDecryptException.class);
    }

    @Test
    void aadBindsCiphertextToItsRow() {
        SecretVault vault = vault(KEY_A);
        UUID clusterA = UUID.randomUUID();
        UUID clusterB = UUID.randomUUID();
        SecretVault.Sealed sealed = vault.encrypt(clusterA, "JOLOKIA_BASIC", "x");

        assertThatThrownBy(() -> vault.decrypt(clusterB, "JOLOKIA_BASIC", sealed.ciphertext(), sealed.nonce()))
                .isInstanceOf(SecretVault.SecretDecryptException.class);
        assertThatThrownBy(() -> vault.decrypt(clusterA, "CORE", sealed.ciphertext(), sealed.nonce()))
                .isInstanceOf(SecretVault.SecretDecryptException.class);
    }

    @Test
    void missingKeyStopsStartup() {
        assertThatThrownBy(() -> vault(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARTEMIS_STUDIO_SECRET_KEY");
    }

    @Test
    void wrongLengthKeyStopsStartup() {
        String twentyBytes = Base64.getEncoder().encodeToString(new byte[20]);
        assertThatThrownBy(() -> vault(twentyBytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void nonBase64KeyStopsStartup() {
        assertThatThrownBy(() -> vault("not valid base64 !!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }
}
