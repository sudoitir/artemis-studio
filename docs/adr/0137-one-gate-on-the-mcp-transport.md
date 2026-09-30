# ADR-0137: One gate on the MCP transport narrows, hides and audits every call

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi

## Context

Three requirements need Studio code on the path of every MCP request:

- a token restricted to named tools;
- an installation-wide read-only mode;
- an audit row for every tool call, reads included.

Built-in tools are `@McpTool` beans registered by Spring AI's annotation scanner. Plugin tools are
added at runtime by `McpPluginBridge`. `tools/list` is built inside the SDK's
`McpStatelessAsyncServer`. And `McpServerInstructions` wrote one instruction text naming every
tool, so a restricted token would still have been told about the tools it may not use.

ADR-0046 says the MCP layer adds no check of its own, because a second copy of policy drifts.

## Decision

**`McpGate` wraps the SDK's request handler at the transport.** A `@Primary
McpStatelessServerTransport` bean wraps the autoconfigured `WebMvcStatelessServerTransport`. The
router keeps its exact-type bean, and the server takes the interface. Its `setMcpHandler` installs
the SDK's handler on the real transport wrapped in the gate. The gate runs on the servlet thread,
where the caller's security context is, and runs the delegate there eagerly.

- `initialize`: the instructions are generated per caller from the offered tools.
  `McpServerInstructions` is deleted.
- `tools/list`: tools outside the token's allow-list, and MUTATE tools in read-only mode, are
  removed.
- `tools/call`:
  - Every call writes `MCP_TOOL_CALL` (target type `mcp-tool`, the tool name, the `clusterId`
    argument, scalar arguments without message content or confirmations) under the caller's actor.
  - The call is bound as `AuditScope.PARENT` (ADR-0093), so the rows the underlying service writes
    nest under it.
  - A tool outside the allow-list answers word for word what the SDK answers for an unknown tool.
  - A MUTATE tool in read-only mode gets an error result, whatever its `dryRun` or `confirm`.

**`McpToolCatalog` decides what is offered.** `studio_help` is always offered. `studio_help`,
`studio://tools` and the instructions all describe only offered tools, and `studio://permissions`
shows the allow-list. The allow-list is `api_token.mcp_tools` (empty means every tool), carried on
`TokenPrincipal` and chosen at mint from `GET /api/v1/mcp/tools`. Read-only is the setting
`mcp.read-only`.

This does not contradict ADR-0046. The gate copies no permission check: grants, `ClusterAccessGuard`
and `@PreAuthorize` still decide what a call may do. It only narrows the surface further and adds
attribution.

## Consequences

- One place, built-in and plugin tools alike, and nothing for a plugin author to do.
- Every read through MCP writes an audit row. That is what the spec asks for, and it is small next
  to the broker work a call does.
- Read-only works per tool, from its posture. A MUTATE tool that also has a read operation
  (`studio_setting` with `op=get`, `alert_rule` listing) is hidden as a whole, and those reads are
  unavailable through MCP while read-only is on. The REST API and the console still serve them.
- Only identifying arguments (cluster, queue, address, operation and the like) are written by
  value. Every other argument, including message content, broker XML and setting values, is
  recorded by name only.
- The gate depends on the SDK's handler interface. `McpGateIntegrationTest` lists and calls tools
  through the real endpoint, so an SDK change that bypasses the gate fails the build.

## Alternatives considered

- **A `BeanPostProcessor` over the scanner's tool specifications.** It covers calls of built-in
  tools only and cannot filter `tools/list`.
- **A servlet filter re-parsing JSON-RPC bodies.** A second protocol parser, outside the SDK's
  error handling.
- **Per-token read-only as a flag.** A token granted only read permissions already is read-only.
  A second flag would be a second model of the same thing.
