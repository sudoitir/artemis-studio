package io.github.sudoitir.artemisstudio.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.kernel.security.SecretProviderProperties;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.core.VaultVersionedKeyValueTemplate;
import org.springframework.vault.support.Versioned;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

@Testcontainers
class VaultKeyProviderTest {

    private static final String TOKEN = "root-token";

    @Container
    static final VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:1.19").withVaultToken(TOKEN);

    static VaultVersionedKeyValueTemplate kv;

    @BeforeAll
    static void connect() {
        var template = new VaultTemplate(
                VaultEndpoint.from(java.net.URI.create(VAULT.getHttpHostAddress())), new TokenAuthentication(TOKEN));
        kv = new VaultVersionedKeyValueTemplate(template, "secret");
    }

    private static String b64(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return Base64.getEncoder().encodeToString(k);
    }

    private static VaultKeyProvider provider(String path) {
        return new VaultKeyProvider(new SecretProviderProperties.Vault(
                VAULT.getHttpHostAddress(), "secret", path, null, "token", TOKEN, null, null, null, null));
    }

    @Test
    void everyLiveVersionIsAKeyVersionAndTheOidcSecretComesFromTheLatest() {
        kv.put("two-versions", Map.of("kek", b64(1)));
        kv.put("two-versions", Map.of("kek", b64(2), "oidc-client-secret", "s3cret"));

        var provider = provider("two-versions");

        assertThat(provider.load().keys()).containsOnlyKeys(1, 2);
        assertThat(provider.secret("oidc-client-secret")).contains("s3cret");
        assertThat(provider.secret("absent")).isEmpty();
    }

    @Test
    void theOidcSecretComesFromItsOwnPathWhenSet() {
        kv.put("keys-only", Map.of("kek", b64(1)));
        kv.put("oidc-only", Map.of("oidc-client-secret", "own-path"));
        var provider = new VaultKeyProvider(new SecretProviderProperties.Vault(
                VAULT.getHttpHostAddress(),
                "secret",
                "keys-only",
                "oidc-only",
                "token",
                TOKEN,
                null,
                null,
                null,
                null));

        assertThat(provider.load().keys()).containsOnlyKeys(1);
        assertThat(provider.secret("oidc-client-secret")).contains("own-path");
        provider.destroy();
    }

    @Test
    void aDestroyedVersionIsNotAKeyVersion() {
        kv.put("destroyed", Map.of("kek", b64(1)));
        kv.put("destroyed", Map.of("kek", b64(2)));
        kv.destroy("destroyed", Versioned.Version.from(1));

        assertThat(provider("destroyed").load().keys()).containsOnlyKeys(2);
    }

    @Test
    void aMissingPathFailsNamingVaultAndThePath() {
        assertThatThrownBy(() -> provider("nothing/here").load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'vault'")
                .hasMessageContaining("nothing/here")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void aDeniedTokenFailsWithoutRevealingIt() {
        var denied = new VaultKeyProvider(new SecretProviderProperties.Vault(
                VAULT.getHttpHostAddress(),
                "secret",
                "two-versions",
                null,
                "token",
                "wrong-token",
                null,
                null,
                null,
                null));

        assertThatThrownBy(denied::load)
                .hasMessageContaining("'vault'")
                .hasMessageContaining("two-versions")
                .hasMessageNotContaining("wrong-token");
    }

    @Test
    void anUnreachableVaultFailsNamingVault() {
        var unreachable = new VaultKeyProvider(new SecretProviderProperties.Vault(
                "http://127.0.0.1:1", "secret", "any", null, "token", TOKEN, null, null, null, null));

        assertThatThrownBy(unreachable::load).hasMessageContaining("'vault'").hasMessageContaining("any");
    }
}
