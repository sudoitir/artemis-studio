package io.github.sudoitir.artemisstudio.platform.mcp;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;

/** The agent surface over MCP. Module descriptor (ADR-0070). */
public final class McpModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("mcp")
            .title("MCP server")
            .kind(FeatureDescriptor.Kind.PLATFORM)
            .build();

    private McpModule() {}
}
