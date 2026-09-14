package io.github.sudoitir.artemisstudio.kernel.security;

/**
 * A way to establish who a caller is (ADR-0073). Every provider ends in the same
 * {@link StudioPrincipal}, so nothing downstream of authentication knows or cares which one
 * was used. A module offers providers through {@link IdentityProviders}.
 */
public sealed interface IdentityProvider
        permits CredentialIdentityProvider, RedirectIdentityProvider, BearerIdentityProvider {

    /** Stable identifier, named by a login request and by group mappings. */
    String id();

    /** What an operator reads on the login screen. */
    String label();
}
