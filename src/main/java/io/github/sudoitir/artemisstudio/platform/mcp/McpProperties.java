package io.github.sudoitir.artemisstudio.platform.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Result caps for the MCP surface (ADR-0045). Deliberately tighter than the REST defaults:
 * a model pays for every row it reads, and the broker pays for every row it did not need.
 * Mutation volume gets no second ceiling here — it inherits the bulk cap.
 */
@ConfigurationProperties(prefix = "artemis-studio.mcp")
public record McpProperties(
        @DefaultValue("25") int defaultLimit,
        @DefaultValue("100") int maxLimit) {

    /** Clamps a caller-supplied limit into {@code 1..maxLimit}, defaulting a null. */
    public int clamp(Integer requested) {
        if (requested == null) {
            return defaultLimit;
        }
        return Math.clamp(requested, 1, maxLimit);
    }
}
