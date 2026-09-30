package io.github.sudoitir.artemisstudio.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.kernel.security.SecretProviderProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class KeyProvidersTest {

    private static String b64(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return Base64.getEncoder().encodeToString(k);
    }

    private static EnvKeyProvider env(String key) {
        MockEnvironment environment = new MockEnvironment();
        if (key != null) {
            environment.setProperty("artemis-studio.secret-key", key);
        }
        return new EnvKeyProvider(environment);
    }

    @Test
    void envBareKeyIsVersionOne() {
        assertThat(env(b64(1)).load().keys()).containsOnlyKeys(1);
    }

    @Test
    void envVersionedKeys() {
        var keyring = env("1=" + b64(1) + ", 2=" + b64(2)).load();

        assertThat(keyring.keys()).containsOnlyKeys(1, 2);
        assertThat(keyring.highest()).isEqualTo(2);
        assertThat(keyring.get(3)).isEmpty();
    }

    @Test
    void envMissingBlankWrongLengthAndRepeatedFailNamingTheProvider() {
        assertThatThrownBy(() -> env(null).load())
                .hasMessageContaining("'env'")
                .hasMessageContaining("ARTEMIS_STUDIO_SECRET_KEY");
        assertThatThrownBy(() -> env(" ").load()).hasMessageContaining("ARTEMIS_STUDIO_SECRET_KEY");
        assertThatThrownBy(() -> env("1=" + b64(1) + ",2=" + Base64.getEncoder().encodeToString(new byte[20]))
                        .load())
                .hasMessageContaining("key version 2")
                .hasMessageContaining("32-byte key required");
        assertThatThrownBy(() -> env("1=" + b64(1) + ",1=" + b64(2)).load()).hasMessageContaining("version 1");
    }

    @Test
    void envSecretReadsTheMatchingVariable() {
        MockEnvironment environment = new MockEnvironment().withProperty("ARTEMIS_STUDIO_OIDC_CLIENT_SECRET", "s");

        assertThat(new EnvKeyProvider(environment).secret("oidc-client-secret")).contains("s");
        assertThat(new EnvKeyProvider(new MockEnvironment()).secret("oidc-client-secret"))
                .isEmpty();
    }

    @Test
    void fileReadsKekFilesAndSecrets(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("kek-1"), b64(1) + "\n");
        Files.writeString(dir.resolve("kek-3"), b64(3));
        Files.writeString(dir.resolve("oidc-client-secret"), "shh\n");
        FileKeyProvider provider = new FileKeyProvider(dir.toString());

        assertThat(provider.load().keys()).containsOnlyKeys(1, 3);
        assertThat(provider.secret("oidc-client-secret")).contains("shh");
        assertThat(provider.secret("absent")).isEmpty();
    }

    @Test
    void fileFailsNamingTheProviderAndTheVersionFile(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> new FileKeyProvider("")).hasMessageContaining("'file'");
        assertThatThrownBy(() -> new FileKeyProvider(dir.resolve("nope").toString()).load())
                .hasMessageContaining("'file'");
        assertThatThrownBy(() -> new FileKeyProvider(dir.toString()).load()).hasMessageContaining("no kek-<n> file");
        Files.writeString(dir.resolve("kek-2"), "AAAA");
        assertThatThrownBy(() -> new FileKeyProvider(dir.toString()).load())
                .hasMessageContaining("kek-2")
                .hasMessageContaining("32-byte key required");
    }

    @Test
    void selectionByProviderName() {
        KeyProviderConfig config = new KeyProviderConfig();
        MockEnvironment environment = new MockEnvironment();

        assertThat(config.keyProvider(
                                new SecretProviderProperties("env", new SecretProviderProperties.File(null)),
                                environment)
                        .name())
                .isEqualTo("env");
        assertThatThrownBy(() -> config.keyProvider(
                        new SecretProviderProperties("nope", new SecretProviderProperties.File(null)), environment))
                .hasMessageContaining("artemis-studio.secrets.provider 'nope'");
    }
}
