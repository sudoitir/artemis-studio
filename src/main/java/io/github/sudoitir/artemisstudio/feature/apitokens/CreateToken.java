package io.github.sudoitir.artemisstudio.feature.apitokens;

import io.github.sudoitir.artemisstudio.kernel.plugin.ResourceKind;
import io.github.sudoitir.artemisstudio.kernel.security.ResourcePattern;
import io.github.sudoitir.artemisstudio.kernel.security.TokenGrant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The parameters of the gated {@code token.create} operation: everything that shapes the token and nothing secret,
 * since the secret is made only when the token is.
 *
 * @param userId the owner
 * @param expiresAt when it stops working
 * @param mcpTools the MCP tools it may call; empty for all
 * @param mintedWithMfa whether the session creating it had verified a second factor
 */
public record CreateToken(
        UUID userId, String name, Instant expiresAt, List<Grant> grants, List<String> mcpTools, boolean mintedWithMfa) {

    public CreateToken {
        grants = List.copyOf(grants);
        mcpTools = List.copyOf(mcpTools);
    }

    /**
     * A permission the token is narrowed to, as plain strings.
     *
     * @param resourceKind {@code null} for the whole scope
     * @param resourcePattern {@code null} exactly when {@code resourceKind} is
     */
    public record Grant(String scopeType, UUID scopeId, String action, String resourceKind, String resourcePattern) {

        static Grant of(TokenGrant grant) {
            return new Grant(
                    grant.scopeType().name(),
                    grant.scopeId(),
                    grant.action(),
                    grant.limited() ? grant.kind().name() : null,
                    grant.limited() ? grant.pattern().text() : null);
        }

        TokenGrant toGrant() {
            io.github.sudoitir.artemisstudio.kernel.security.Grant.ScopeType scope =
                    io.github.sudoitir.artemisstudio.kernel.security.Grant.ScopeType.valueOf(scopeType);
            return resourceKind == null
                    ? TokenGrant.of(scope, scopeId, action)
                    : new TokenGrant(
                            scope,
                            scopeId,
                            action,
                            ResourceKind.valueOf(resourceKind),
                            ResourcePattern.parse(resourcePattern));
        }
    }
}
