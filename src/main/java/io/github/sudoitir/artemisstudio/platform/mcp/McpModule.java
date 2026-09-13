package io.github.sudoitir.artemisstudio.platform.mcp;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.McpToolDef;
import java.util.List;

/** The agent surface over MCP. Module descriptor (ADR-0070). */
public final class McpModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("mcp")
            .title("MCP server")
            .kind(FeatureDescriptor.Kind.PLATFORM)
            .mcpTool(new McpToolDef(
                    McpToolCatalog.HELP_TOOL,
                    McpToolDef.Posture.READ,
                    "This index, or one tool's accepted values and body shapes.",
                    List.of(McpToolDef.Param.note("topic", "A tool name. Omit for the index of every tool."))))
            .build();

    private McpModule() {}
}
