package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.security.KeyProvider;
import io.github.sudoitir.artemisstudio.kernel.security.Keyring;
import io.github.sudoitir.artemisstudio.kernel.security.SecretProviderProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Keys from one Kubernetes Secret read through the API server with the pod's service-account token: {@code kek-<n>}
 * keys (base64 inside the Secret's own base64) and named secrets. The CA file is trusted for an {@code https} API
 * URL. Failures name the provider, the namespace and the secret, never a token or key.
 */
class KubernetesKeyProvider implements KeyProvider {

    private static final Pattern KEK_KEY = Pattern.compile("kek-(\\d+)");
    private static final String NAMESPACE_FILE = "/var/run/secrets/kubernetes.io/serviceaccount/namespace";

    private final SecretProviderProperties.Kubernetes config;
    private final ObjectMapper json = new ObjectMapper();
    private final String namespace;

    KubernetesKeyProvider(SecretProviderProperties.Kubernetes config) {
        if (config.secretName() == null || config.secretName().isBlank()) {
            throw new IllegalStateException(
                    "Secret key provider 'kubernetes': artemis-studio.secrets.kubernetes.secret-name is not set.");
        }
        this.config = config;
        this.namespace = config.namespace() != null && !config.namespace().isBlank()
                ? config.namespace()
                : readFile(NAMESPACE_FILE, "namespace is not set and cannot be read from " + NAMESPACE_FILE);
    }

    @Override
    public Keyring load() {
        JsonNode data = fetch();
        TreeMap<Integer, SecretKey> keys = new TreeMap<>();
        data.properties().forEach(entry -> {
            Matcher kek = KEK_KEY.matcher(entry.getKey());
            if (kek.matches()) {
                int version = Integer.parseInt(kek.group(1));
                keys.put(version, Keyring.parse(name(), "key version " + version, decode(entry.getValue())));
            }
        });
        if (keys.isEmpty()) {
            throw new IllegalStateException(failure("has no kek-<n> key"));
        }
        return new Keyring(keys);
    }

    @Override
    public Optional<String> secret(String name) {
        JsonNode value = fetch().get(name);
        return value == null
                ? Optional.empty()
                : Optional.of(decode(value).trim()).filter(v -> !v.isEmpty());
    }

    @Override
    public String name() {
        return "kubernetes";
    }

    private JsonNode fetch() {
        URI uri = URI.create(config.apiUrl() + "/api/v1/namespaces/" + namespace + "/secrets/" + config.secretName());
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + readFile(config.tokenPath(), "cannot read the token"))
                    .header("Accept", "application/json")
                    .build();
            HttpResponse<String> response = client(uri).send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        failure("was refused by the API server (HTTP " + response.statusCode() + ")"));
            }
            JsonNode data = json.readTree(response.body()).get("data");
            if (data == null || !data.isObject()) {
                throw new IllegalStateException(failure("holds no data"));
            }
            return data;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure("read was interrupted"), e);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(failure("cannot be read from the API server"), e);
        }
    }

    private HttpClient client(URI uri) throws IOException, GeneralSecurityException {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if ("https".equals(uri.getScheme())) {
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            try (InputStream ca = Files.newInputStream(Path.of(config.caPath()))) {
                int i = 0;
                for (Certificate certificate :
                        CertificateFactory.getInstance("X.509").generateCertificates(ca)) {
                    trust.setCertificateEntry("ca-" + i++, certificate);
                }
            }
            TrustManagerFactory trustManagers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trust);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, trustManagers.getTrustManagers(), null);
            builder.sslContext(tls);
        }
        return builder.build();
    }

    private String decode(JsonNode base64) {
        try {
            return new String(Base64.getDecoder().decode(base64.asString("")), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(failure("holds a value that is not base64"));
        }
    }

    private String readFile(String path, String problem) {
        try {
            return Files.readString(Path.of(path)).trim();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Secret key provider 'kubernetes': " + problem + " (secret " + (namespace == null ? "?" : namespace)
                            + "/" + config.secretName() + ").",
                    e);
        }
    }

    private String failure(String what) {
        return "Secret key provider 'kubernetes': secret " + namespace + "/" + config.secretName() + " " + what + ".";
    }
}
