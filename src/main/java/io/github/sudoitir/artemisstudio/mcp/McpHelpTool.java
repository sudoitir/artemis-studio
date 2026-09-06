package io.github.sudoitir.artemisstudio.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The discovery route (ADR-0054).
 *
 * <p>ADR-0050 moved the enum members and body shapes out of the tool schemas and
 * into {@code studio://tools}, reasoning that a host fetches a resource once and
 * keeps it. That is true of hosts that read resources. {@code resources} is an
 * optional server capability, and nothing in the MCP specification obliges a client
 * to call {@code resources/read} — ever. On a host that skips them, ADR-0050 did not
 * make discovery progressive; it made it absent, and a model was left inferring
 * valid values by triggering rejections.
 *
 * <p>Tools are the one part of MCP every host implements, so the detail lives behind
 * one. It costs about thirty-five tokens in {@code tools/list} and pays for itself
 * several times over: because this channel is reliable, every other schema could
 * drop the hedging text — spelled defaults, type codes, "see studio://tools"
 * pointers — that existed only in case the resource was never read.
 *
 * <p>Read-only and free of side effects, so a host has no reason to gate it and a
 * model has no reason to hesitate before calling it.
 */
@Component
public class McpHelpTool {

    @McpTool(
            name = "studio_help",
            description = "Accepted values, JSON body shapes and semantics the tool schemas leave out. "
                    + "Call with no topic for the index of every tool, or a tool name for its detail.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult studioHelp(@McpToolParam(required = false) String topic) {
        return McpErrors.guard(() -> {
            if (topic == null || topic.isBlank()) {
                return Map.of(
                        "tools",
                        McpToolCatalog.index(),
                        "next",
                        "Call studio_help with a tool name for its accepted values and body shapes.");
            }
            McpToolCatalog.Entry entry = McpToolCatalog.find(topic.trim());
            if (entry == null) {
                // Not an error: an unknown topic is a model guessing a name, and the
                // useful answer is the list it should have guessed from.
                return Map.of(
                        "unknown", topic.trim(),
                        "tools", McpToolCatalog.toolNames(),
                        "next", "Call studio_help with one of these, or with no topic for the full index.");
            }
            return List.of(entry);
        });
    }
}
