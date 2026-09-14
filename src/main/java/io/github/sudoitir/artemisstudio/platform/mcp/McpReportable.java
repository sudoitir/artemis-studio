package io.github.sudoitir.artemisstudio.platform.mcp;

/**
 * An exception whose message tells a model how to fix its call. {@link McpErrors} returns
 * {@link #mcpMessage()} as the tool result, so the MCP layer needs no knowledge of the module
 * that threw it.
 */
public interface McpReportable {

    String mcpMessage();
}
