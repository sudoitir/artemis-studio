package io.github.sudoitir.artemisstudio.feature.apitokens;

import io.github.sudoitir.artemisstudio.kernel.security.BearerIdentityProvider;
import io.github.sudoitir.artemisstudio.kernel.security.IdentityProviders;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Personal API tokens as a bearer identity provider, for automation and MCP clients (ADR-0039, ADR-0046). */
@Component
@RequiredArgsConstructor
class ApiTokenIdentity implements IdentityProviders, BearerIdentityProvider {

    private final ApiTokenService tokens;

    @Override
    public List<ApiTokenIdentity> providers() {
        return List.of(this);
    }

    @Override
    public String id() {
        return "api-token";
    }

    @Override
    public String label() {
        return "API token";
    }

    @Override
    public Optional<StudioPrincipal> authenticate(String token) {
        return Optional.ofNullable(tokens.authenticate(token));
    }
}
