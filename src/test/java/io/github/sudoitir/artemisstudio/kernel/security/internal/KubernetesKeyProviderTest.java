package io.github.sudoitir.artemisstudio.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.sudoitir.artemisstudio.kernel.security.SecretProviderProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A stub API server over plain HTTP: the CA is only read for an https API URL. */
class KubernetesKeyProviderTest {

    private static String b64(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return Base64.getEncoder().encodeToString(k);
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @TempDir
    Path dir;

    HttpServer server;
    int status = 200;
    String body = "";
    final AtomicReference<String> seenPath = new AtomicReference<>();
    final AtomicReference<String> seenAuthorization = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private KubernetesKeyProvider provider() throws IOException {
        Path token = Files.writeString(dir.resolve("token"), "sa-token\n");
        return new KubernetesKeyProvider(new SecretProviderProperties.Kubernetes(
                "studio",
                "studio-keys",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                token.toString(),
                null));
    }

    @Test
    void readsTheKeyringAndTheOidcSecretFromTheNamedSecret() throws IOException {
        body = """
                {"data": {"kek-1": "%s", "kek-2": "%s", "oidc-client-secret": "%s"}}""".formatted(encoded(b64(1)), encoded(b64(2)), encoded("s3cret"));
        var provider = provider();

        assertThat(provider.load().keys()).containsOnlyKeys(1, 2);
        assertThat(provider.secret("oidc-client-secret")).contains("s3cret");
        assertThat(provider.secret("absent")).isEmpty();
        assertThat(seenPath.get()).isEqualTo("/api/v1/namespaces/studio/secrets/studio-keys");
        assertThat(seenAuthorization.get()).isEqualTo("Bearer sa-token");
    }

    @Test
    void aRefusedRequestFailsNamingKubernetesTheNamespaceAndTheSecret() throws IOException {
        status = 403;
        body = "{\"message\": \"forbidden\"}";
        var provider = provider();

        assertThatThrownBy(provider::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'kubernetes'")
                .hasMessageContaining("studio/studio-keys")
                .hasMessageContaining("403")
                .hasMessageNotContaining("sa-token");
    }

    @Test
    void aSecretWithoutKeysFails() throws IOException {
        body = "{\"data\": {\"other\": \"%s\"}}".formatted(encoded("x"));
        var provider = provider();

        assertThatThrownBy(provider::load).hasMessageContaining("'kubernetes'").hasMessageContaining("kek-<n>");
    }
}
