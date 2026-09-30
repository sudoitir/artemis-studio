package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.Optional;

/**
 * Where Studio's key-encryption keys, and the secrets an operator keeps beside them, come from. One provider is
 * selected by {@code artemis-studio.secrets.provider}; there is no fallback between providers.
 */
public interface KeyProvider {

    /**
     * The keys it holds now.
     *
     * @throws IllegalStateException with a message naming the provider when it cannot deliver a valid keyring
     */
    Keyring load();

    /** A named secret such as {@code oidc-client-secret}, when the provider holds it. */
    Optional<String> secret(String name);

    /** The value of {@code artemis-studio.secrets.provider} that selects this provider. */
    String name();
}
