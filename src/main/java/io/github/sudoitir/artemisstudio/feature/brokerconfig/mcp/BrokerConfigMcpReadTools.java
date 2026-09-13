package io.github.sudoitir.artemisstudio.feature.brokerconfig.mcp;

import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigApplyService;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigService;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.ConfigDiffService;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.web.ConfigViews;
import io.github.sudoitir.artemisstudio.platform.mcp.McpArgs;
import io.github.sudoitir.artemisstudio.platform.mcp.McpErrors;
import io.github.sudoitir.artemisstudio.platform.mcp.McpViews;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The {@code broker_config} and {@code config_diff} MCP tools. *
 * <p>Reads only: nothing here needs the dry-run/confirm contract, which is the review
 * boundary ADR-0045 draws between read and mutating tool classes. Every method is a thin
 * adapter over the services the REST layer already uses.
 */
@Component
@RequiredArgsConstructor
public class BrokerConfigMcpReadTools {

    private final BrokerConfigService brokerConfig;
    private final BrokerConfigApplyService brokerConfigApply;
    private final ConfigDiffService configDiff;
    private final io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory nodeRepo;

    public enum ConfigReadKind {
        DECLARATION,
        DRIFT,
        XML,
        APPLIES
    }

    /**
     * A cluster's declared configuration and what the brokers make of it (ADR-0067):
     * the declaration, the last drift evaluation per node, the {@code broker.xml}
     * fragment, or the apply history — one tool, one {@code kind}.
     */
    @McpTool(
            name = "broker_config",
            description =
                    "A cluster's declared broker configuration: declaration, drift per node, XML fragment, or applies.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult brokerConfig(
            @McpToolParam(required = true) String clusterId, @McpToolParam(required = false) String kind) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        ConfigReadKind what = McpArgs.enumOf(ConfigReadKind.class, "kind", kind, ConfigReadKind.DECLARATION);
        return McpErrors.guard(() -> switch (what) {
            case DECLARATION -> declaration(brokerConfig.get(id), true);
            case DRIFT -> declaration(brokerConfig.get(id), false);
            case XML -> brokerConfig.exportXml(id, null);
            case APPLIES ->
                brokerConfigApply.history(id, 20).stream()
                        .map(a -> new McpViews.ConfigApply(
                                a.getId(),
                                a.getStartedAt(),
                                a.isDryRun(),
                                a.getOutcome(),
                                a.getSummary(),
                                a.getActor(),
                                a.getAuditEventId()))
                        .toList();
        });
    }

    static McpViews.ConfigDeclaration declaration(BrokerConfigService.Declaration d, boolean withDocument) {
        List<McpViews.ConfigNodeState> nodes = d.nodes().stream()
                .map(n -> new McpViews.ConfigNodeState(
                        n.nodeName(),
                        n.live(),
                        n.state().name(),
                        n.detail(),
                        n.evaluatedAt(),
                        n.findings().stream()
                                .map(f -> new McpViews.ConfigFinding(
                                        f.kind().name(), f.section().name(), f.key(), f.detail()))
                                .toList()))
                .toList();
        return new McpViews.ConfigDeclaration(
                d.declared(),
                d.revision(),
                d.applyMode() == null ? null : d.applyMode().name(),
                d.updatedBy(),
                d.updatedAt(),
                withDocument ? d.document() : null,
                nodes);
    }

    @McpTool(
            name = "config_diff",
            description = "Classified config differences between two nodes. Omit nodeB for the first two.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult configDiff(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = false) String nodeA,
            @McpToolParam(required = false) String nodeB) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        UUID a = McpArgs.optionalUuid("nodeA", nodeA);
        UUID b = McpArgs.optionalUuid("nodeB", nodeB);
        return McpErrors.guard(() -> diff(id, a, b));
    }

    private McpViews.ConfigDiff diff(UUID clusterId, UUID nodeA, UUID nodeB) {
        // A model asking "do these nodes agree" rarely has node ids to hand, so the
        // pair defaults to the cluster's first two rather than making it ask twice.
        if (nodeA == null || nodeB == null) {
            var nodes = nodeRepo.nodes(clusterId);
            if (nodes.size() < 2) {
                throw new io.github.sudoitir.artemisstudio.kernel.core.ConflictException(
                        "single-node-cluster",
                        "This cluster has fewer than two nodes, so there is nothing to compare.");
            }
            nodeA = nodeA != null ? nodeA : nodes.get(0).getId();
            UUID finalA = nodeA;
            nodeB = nodeB != null
                    ? nodeB
                    : nodes.stream()
                            .map(n -> n.getId())
                            .filter(n -> !n.equals(finalA))
                            .findFirst()
                            .orElseThrow();
        }
        ConfigViews.ConfigDiffView view = configDiff.compare(clusterId, nodeA, nodeB);
        List<McpViews.ConfigDifference> items = new ArrayList<>();
        for (ConfigViews.ConfigSectionView section : view.sections()) {
            for (ConfigViews.ConfigEntryView entry : section.entries()) {
                // Only the drifting keys: a model does not need the hundreds that agree,
                // and shipping them is the single largest response-size risk in this tool.
                if (entry.drift()) {
                    items.add(new McpViews.ConfigDifference(
                            section.section() + "/" + entry.key(),
                            entry.classification(),
                            entry.left(),
                            entry.right()));
                }
            }
        }
        return new McpViews.ConfigDiff(
                clusterId, view.left().nodeName(), view.right().nodeName(), view.driftCount(), items);
    }
}
