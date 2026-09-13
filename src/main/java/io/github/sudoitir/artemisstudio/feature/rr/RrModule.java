package io.github.sudoitir.artemisstudio.feature.rr;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.McpToolDef;
import io.github.sudoitir.artemisstudio.kernel.plugin.TopicDef;
import java.util.List;

/** Request-reply flows, expectations and latency. Module descriptor (ADR-0070). */
public final class RrModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("rr")
            .title("Request-reply tracing")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .apiPrefix("/api/v1/clusters/{clusterId}/rr")
            .settingKey(RrSettings.RETENTION_DAYS)
            .settingKey(RrSettings.REAPER_CRON)
            .settingKey(RrSettings.DEFAULT_DEADLINE_MS)
            .settingKey(RrSettings.PAYLOAD_CAPTURE_BYTES)
            .settingKey(RrSettings.SWEEP_INTERVAL)
            .settingKey(RrSettings.SAMPLE_INTERVAL)
            .streamTopic(TopicDef.signal("rr"))
            .mcpTool(new McpToolDef(
                    "trace_request_reply",
                    McpToolDef.Posture.READ,
                    "Request-reply tracing: flows, latency and timeouts, expectations, or why there are none.",
                    List.of(
                            McpToolDef.Param.values(
                                    "mode", List.of("flows", "stats", "expectations", "diagnostics"), "Default flows."),
                            McpToolDef.Param.note("window", "e.g. 15m. Defaults to 15m."))))
            .build();

    private RrModule() {}
}
