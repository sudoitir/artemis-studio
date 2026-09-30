package io.github.sudoitir.artemisstudio.kernel.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Which {@link KeyProvider} supplies the keys, and the settings of each provider that needs any. */
@ConfigurationProperties(prefix = "artemis-studio.secrets")
public record SecretProviderProperties(
        @DefaultValue("env") String provider,
        @DefaultValue File file,
        @DefaultValue Vault vault,
        @DefaultValue Kubernetes kubernetes) {

    /** {@code kek-<n>} files (base64) and {@code oidc-client-secret}; a mounted Kubernetes Secret fits. */
    public record File(String directory) {}

    /**
     * KV version 2 at {@code mount}/{@code path}: every live version's {@code kek} field is a key version, and
     * {@code oidc-client-secret} is read from the latest version of {@code oidcPath}, which defaults to {@code path};
     * keeping it on its own path stops an edit to it from pushing a key version out of KV's version history.
     *
     * @param authentication {@code token}, {@code approle} or {@code kubernetes}
     */
    public record Vault(
            String uri,
            @DefaultValue("secret") String mount,
            String path,
            String oidcPath,
            @DefaultValue("token") String authentication,
            String token,
            String roleId,
            String secretId,
            String kubernetesRole,

            @DefaultValue("/var/run/secrets/kubernetes.io/serviceaccount/token")
            String kubernetesTokenPath) {}

    /**
     * One Kubernetes Secret read through the API server: keys {@code kek-<n>} and {@code oidc-client-secret}. A blank
     * namespace is read from the service-account mount.
     */
    public record Kubernetes(
            String namespace,
            String secretName,
            @DefaultValue("https://kubernetes.default.svc") String apiUrl,

            @DefaultValue("/var/run/secrets/kubernetes.io/serviceaccount/token")
            String tokenPath,

            @DefaultValue("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt")
            String caPath) {}
}
