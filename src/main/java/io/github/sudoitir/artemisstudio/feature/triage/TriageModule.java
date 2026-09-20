package io.github.sudoitir.artemisstudio.feature.triage;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.McpToolDef;
import java.util.List;

/** Cross-feature triage: consumer health over REST, diagnosis over MCP. Module descriptor (ADR-0070). */
public final class TriageModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("triage")
            .title("Triage")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .apiPrefix("/api/v1/clusters/{clusterId}/consumer-health")
            .require("resources")
            .require("metrics")
            .require("events")
            .mcpTool(new McpToolDef(
                    "diagnose",
                    McpToolDef.Posture.READ,
                    "Health of a cluster, or of one queue end to end. Start here.",
                    List.of(McpToolDef.Param.note(
                            "queue",
                            "Omit for the cluster: HA role per node, who is live, split-brain, replication "
                                    + "lag, clock skew and firing alerts. Give a queue name for that queue: its "
                                    + "consumer-health verdict with the depth, trend, enqueue and acknowledge "
                                    + "rates behind it, plus DLQ hints and recent events."))))
            .mcpTool(new McpToolDef(
                    "activity_log",
                    McpToolDef.Posture.READ,
                    "Broker events, or Studio's own audit trail.",
                    List.of(McpToolDef.Param.values(
                            "source", List.of("broker_events", "audit"), "Default broker_events."))))
            .build();

    private TriageModule() {}
}
