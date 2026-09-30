package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.security.KeyProvider;
import io.github.sudoitir.artemisstudio.kernel.security.Keyring;
import io.github.sudoitir.artemisstudio.kernel.security.SecretProviderProperties;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.vault.authentication.AppRoleAuthentication;
import org.springframework.vault.authentication.AppRoleAuthenticationOptions;
import org.springframework.vault.authentication.AppRoleAuthenticationOptions.RoleId;
import org.springframework.vault.authentication.AppRoleAuthenticationOptions.SecretId;
import org.springframework.vault.authentication.ClientAuthentication;
import org.springframework.vault.authentication.KubernetesAuthentication;
import org.springframework.vault.authentication.KubernetesAuthenticationOptions;
import org.springframework.vault.authentication.LifecycleAwareSessionManager;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultClients;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.core.VaultVersionedKeyValueTemplate;
import org.springframework.vault.support.VaultMetadataResponse;
import org.springframework.vault.support.Versioned;
import org.springframework.web.client.RestTemplate;

/**
 * Keys from a Vault KV version 2 secret: each version that is neither deleted nor destroyed and has a {@code kek}
 * field (base64) is that key version, and named secrets are fields of the latest version of the OIDC path. All calls
 * share one session, which logs in on first use, renews itself, and is revoked on shutdown. Failures name the
 * provider and the path, never a token or key.
 */
class VaultKeyProvider implements KeyProvider, DisposableBean {

    private final SecretProviderProperties.Vault config;
    private ThreadPoolTaskScheduler scheduler;
    private LifecycleAwareSessionManager session;
    private VaultVersionedKeyValueTemplate kv;

    VaultKeyProvider(SecretProviderProperties.Vault config) {
        require("uri", config.uri());
        require("path", config.path());
        this.config = config;
    }

    private static void require(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Secret key provider 'vault': artemis-studio.secrets.vault." + property + " is not set.");
        }
    }

    @Override
    public Keyring load() {
        return call("cannot read key versions", ops -> {
            VaultMetadataResponse metadata = ops.opsForKeyValueMetadata().get(config.path());
            if (metadata == null) {
                throw new IllegalStateException(failure("has no secret"));
            }
            TreeMap<Integer, SecretKey> keys = new TreeMap<>();
            for (Versioned.Metadata version : metadata.getVersions()) {
                if (version.isDeleted() || version.isDestroyed()) {
                    continue;
                }
                int number = version.getVersion().getVersion();
                Versioned<Map<String, Object>> data = ops.get(config.path(), version.getVersion());
                if (data != null && data.hasData() && data.getRequiredData().get("kek") instanceof String kek) {
                    keys.put(number, Keyring.parse(name(), "key version " + number, kek));
                }
            }
            if (keys.isEmpty()) {
                throw new IllegalStateException(failure("has no live version with a 'kek' field"));
            }
            return new Keyring(keys);
        });
    }

    @Override
    public Optional<String> secret(String name) {
        return call("cannot read " + name, ops -> {
            String path = config.oidcPath() == null || config.oidcPath().isBlank() ? config.path() : config.oidcPath();
            Versioned<Map<String, Object>> latest = ops.get(path);
            if (latest == null || !latest.hasData()) {
                return Optional.<String>empty();
            }
            return Optional.ofNullable(latest.getRequiredData().get(name))
                    .map(Object::toString)
                    .filter(v -> !v.isBlank());
        });
    }

    @Override
    public String name() {
        return "vault";
    }

    private <T> T call(String what, java.util.function.Function<VaultVersionedKeyValueTemplate, T> action) {
        try {
            return action.apply(template());
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            // The cause keeps Vault's own status text (denied, unreachable) for the log; the message stays safe.
            throw new IllegalStateException(failure(what), e);
        }
    }

    private synchronized VaultVersionedKeyValueTemplate template() {
        if (kv == null) {
            VaultEndpoint endpoint = VaultEndpoint.from(URI.create(config.uri()));
            var requests = new JdkClientHttpRequestFactory();
            scheduler = new ThreadPoolTaskScheduler();
            scheduler.setDaemon(true);
            scheduler.setThreadNamePrefix("vault-session-");
            scheduler.initialize();
            session = new LifecycleAwareSessionManager(
                    authentication(endpoint, requests), scheduler, VaultClients.createRestTemplate(endpoint, requests));
            kv = new VaultVersionedKeyValueTemplate(new VaultTemplate(endpoint, requests, session), config.mount());
        }
        return kv;
    }

    /** Revokes the session token and stops its renewal. */
    @Override
    public synchronized void destroy() {
        if (session != null) {
            session.destroy();
            scheduler.shutdown();
            session = null;
            scheduler = null;
            kv = null;
        }
    }

    private ClientAuthentication authentication(VaultEndpoint endpoint, ClientHttpRequestFactory requests) {
        RestTemplate login = VaultClients.createRestTemplate(endpoint, requests);
        return switch (config.authentication()) {
            case "token" -> new TokenAuthentication(config.token());
            case "approle" ->
                new AppRoleAuthentication(
                        AppRoleAuthenticationOptions.builder()
                                .roleId(RoleId.provided(config.roleId()))
                                .secretId(SecretId.provided(config.secretId()))
                                .build(),
                        login);
            case "kubernetes" ->
                new KubernetesAuthentication(
                        KubernetesAuthenticationOptions.builder()
                                .role(config.kubernetesRole())
                                .jwtSupplier(() -> jwt())
                                .build(),
                        login);
            default ->
                throw new IllegalStateException("Secret key provider 'vault': unknown "
                        + "artemis-studio.secrets.vault.authentication '" + config.authentication()
                        + "'; expected token, approle or kubernetes.");
        };
    }

    private String jwt() {
        try {
            return Files.readString(Path.of(config.kubernetesTokenPath())).trim();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read the service-account token", e);
        }
    }

    private String failure(String what) {
        return "Secret key provider 'vault': " + what + " at " + config.mount() + "/" + config.path() + " on "
                + config.uri() + ".";
    }
}
