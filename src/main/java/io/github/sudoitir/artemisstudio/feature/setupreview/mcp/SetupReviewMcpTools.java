package io.github.sudoitir.artemisstudio.feature.setupreview.mcp;

import io.github.sudoitir.artemisstudio.feature.setupreview.SetupReviewService;
import io.github.sudoitir.artemisstudio.platform.mcp.McpArgs;
import io.github.sudoitir.artemisstudio.platform.mcp.McpErrors;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The {@code setup_review} MCP tool (ADR-0106): the latest review, read-only, under the same
 * {@code cluster:read} the REST view needs. It never runs a review — that costs a broker read per
 * node, which a model should not be able to trigger in a loop.
 */
@Component
@RequiredArgsConstructor
public class SetupReviewMcpTools {

    private final SetupReviewService reviews;

    @McpTool(
            name = "setup_review",
            description = "A cluster's configuration review: HA, clustering, durability and message-safety"
                    + " mistakes, each with evidence per node and the broker.xml fix.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult setupReview(@McpToolParam(required = true) String clusterId) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        return McpErrors.guard(() -> reviews.view(id));
    }
}
