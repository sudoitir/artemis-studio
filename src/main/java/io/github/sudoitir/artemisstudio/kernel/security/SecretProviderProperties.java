package io.github.sudoitir.artemisstudio.kernel.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Which {@link KeyProvider} supplies the keys, and the settings of the file provider. */
@ConfigurationProperties(prefix = "artemis-studio.secrets")
public record SecretProviderProperties(
        @DefaultValue("env") String provider, @DefaultValue File file) {

    /** {@code kek-<n>} files (base64) and {@code oidc-client-secret}; a mounted Kubernetes Secret fits. */
    public record File(String directory) {}
}
