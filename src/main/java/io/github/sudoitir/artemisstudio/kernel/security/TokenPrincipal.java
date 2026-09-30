package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.Set;
import java.util.UUID;

/**
 * A principal authenticated by a personal API token: the owner, narrowed to the token's grants,
 * plus the token's id and MCP tool allow-list (ADR-0136, ADR-0137). The request limits, usage
 * counters and the MCP gate read these. Not plugin API; plugins see the {@link StudioPrincipal}.
 */
public final class TokenPrincipal extends StudioPrincipal {

    /**
     * Request attribute set when a request was refused inside a successful response (an MCP tool
     * call answered with a denial), so the token's usage counts it as denied.
     */
    public static final String DENIED_ATTRIBUTE = TokenPrincipal.class.getName() + ".denied";

    private final UUID tokenId;
    private final Set<String> mcpTools;

    public TokenPrincipal(
            UUID userId, String username, Set<Grant> grants, UUID tokenId, String tokenName, Set<String> mcpTools) {
        super(userId, username, grants, false, tokenName);
        this.tokenId = tokenId;
        this.mcpTools = Set.copyOf(mcpTools);
    }

    public UUID tokenId() {
        return tokenId;
    }

    /** Whether the token may call this MCP tool: an empty allow-list allows every tool. */
    public boolean mayCallTool(String tool) {
        return mcpTools.isEmpty() || mcpTools.contains(tool);
    }

    public Set<String> mcpTools() {
        return mcpTools;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TokenPrincipal other && super.equals(other) && tokenId.equals(other.tokenId);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + tokenId.hashCode();
    }
}
