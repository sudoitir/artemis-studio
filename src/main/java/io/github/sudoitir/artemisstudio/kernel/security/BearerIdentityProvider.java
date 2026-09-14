package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.Optional;

/** An {@code Authorization: Bearer} credential, for automation and MCP clients (ADR-0039). */
public non-sealed interface BearerIdentityProvider extends IdentityProvider {

    /** The principal this token identifies, or empty when it is not one of this provider's. */
    Optional<StudioPrincipal> authenticate(String token);
}
