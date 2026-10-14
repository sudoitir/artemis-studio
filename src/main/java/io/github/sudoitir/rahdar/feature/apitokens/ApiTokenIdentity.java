package io.github.sudoitir.rahdar.feature.apitokens;

import io.github.sudoitir.rahdar.kernel.security.BearerIdentityProvider;
import io.github.sudoitir.rahdar.kernel.security.IdentityProviders;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
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
    public Optional<RahdarPrincipal> authenticate(String token) {
        return Optional.<RahdarPrincipal>ofNullable(tokens.authenticate(token));
    }
}
