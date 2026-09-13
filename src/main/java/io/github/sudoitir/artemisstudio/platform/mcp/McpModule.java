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
                    "diagnose",
                    McpToolDef.Posture.READ,
                    "Health of a cluster, or of one queue end to end. Start here.",
                    List.of(McpToolDef.Param.note(
                            "queue",
                            "Omit for the cluster: HA role per node, who is live, split-brain, replication "
                                    + "lag, clock skew and firing alerts. Give a queue name for that queue: depth "
                                    + "and trend, consumers, paused, slow consumers, DLQ and recent events."))))
            .mcpTool(new McpToolDef(
                    "activity_log",
                    McpToolDef.Posture.READ,
                    "Broker events, or Studio's own audit trail.",
                    List.of(McpToolDef.Param.values(
                            "source", List.of("broker_events", "audit"), "Default broker_events."))))
            .mcpTool(new McpToolDef(
                    McpToolCatalog.HELP_TOOL,
                    McpToolDef.Posture.READ,
                    "This index, or one tool's accepted values and body shapes.",
                    List.of(McpToolDef.Param.note("topic", "A tool name. Omit for the index of every tool."))))
            .build();

    private McpModule() {}
}
