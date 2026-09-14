package io.github.sudoitir.artemisstudio.feature.brokerconfig.mcp;

import io.github.sudoitir.artemisstudio.feature.brokerconfig.ConfigDiffService;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.web.ConfigViews;
import io.github.sudoitir.artemisstudio.platform.mcp.McpArgs;
import io.github.sudoitir.artemisstudio.platform.mcp.McpErrors;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

/** The broker configuration module's MCP resources (ADR-0045). */
@Component
@RequiredArgsConstructor
public class BrokerConfigMcpResources {

    private final ConfigDiffService configDiff;

    /**
     * One node's effective broker settings, as a resource rather than a tool.
     *
     * <p>A resource because this is something to look at, not an action to take —
     * and because a host fetches a resource once and keeps it, where a tool is paid
     * for in the listing on every conversation whether or not anyone asks about
     * settings (ADR-0050). Node ids come from {@code cluster://{id}/topology}.
     *
     * <p>These are the settings the node is <em>running with</em>, resolved by the
     * broker. Studio never reads or writes {@code broker.xml}, and this does not
     * mutate anything.
     */
    @McpResource(
            uri = "cluster://{clusterId}/nodes/{nodeId}/settings",
            name = "Node settings",
            description = "One node's effective broker configuration: broker attributes, address settings, "
                    + "security settings and acceptors, as the broker resolves them.",
            mimeType = "application/json")
    public McpSchema.ReadResourceResult nodeSettings(String clusterId, String nodeId) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        UUID node = McpArgs.uuid("nodeId", nodeId);
        ConfigViews.NodeConfigView view = configDiff.nodeConfig(id, node);
        String uri = "cluster://" + clusterId + "/nodes/" + nodeId + "/settings";
        return new McpSchema.ReadResourceResult(
                List.of(new McpSchema.TextResourceContents(uri, "application/json", McpErrors.json(view))));
    }
}
