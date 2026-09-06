# Tasks

The brainstorm and the protocol/library check are done; their findings are in
`design.md` and ADR-0054. This list is the plan they produced.

## Decided before implementation

- [x] Brainstorm the surface shape. Answers to D-Q1 through D-Q6 recorded in
      `design.md`
- [x] Verify what the MCP specification guarantees about `tools/list_changed`, and
      what MCP SDK 2.0.0 / spring-ai 2.0.1 implement. Findings: the notification is
      optional with no client obligation, and `addTool`/`removeTool`/`listTools`
      are server-global with no per-session view or filter extension point
- [x] Check what hosts do with a changed tool list — moot: `protocol: STATELESS`
      has no notification channel, and the global tool table means disclosure
      could not be per-session even in stateful mode
- [x] Write ADR-0054 recording the chosen shape; mark ADR-0050 superseded
- [x] Rewrite this task list to match the decision

## The catalogue

- [x] Add `McpToolCatalog` — one entry per tool: name, posture, one-line summary,
      accepted values and body shapes per parameter. The single source for the
      help tool, the resource and the server instructions
- [x] Replace `McpToolDetail` with the catalogue; delete it
- [x] Generate the server `instructions` block from the catalogue and remove the
      hand-written one from `application.yml` (it had drifted — it omitted
      `queue_lifecycle` and `message_body`)
- [x] Regenerate `studio://tools` from the catalogue as a mirror

## The discovery tool

- [x] Add `studio_help(topic?)` — no topic returns the operation index, a topic
      returns that tool's detail. Read-only posture
- [x] Make `McpArgs` rejections name `studio_help` alongside the accepted values

## The surface

- [x] Merge `cluster_health` + `diagnose_queue` into `diagnose(clusterId, queue?)`
- [x] Merge `message_body` into `browse_messages(..., messageId?)`
- [x] Rename `queue_action` to `message_action`
- [x] Strip the schema descriptions the help tool now makes redundant — spelled
      defaults, type codes, `see studio://tools` pointers

## Enforcement

- [x] Lower the budget ceilings, calibrated against a real `McpToolSchemaBudgetTest`
      run: per-tool 200 → 175, average 160 → 135 (measured 2115/14/151 before,
      1696/13/130 after)
- [x] Assert posture honesty: a tool declaring `readOnlyHint = true` reaches no
      mutating service
- [x] Assert catalogue completeness: every registered tool appears in the
      catalogue, and in the generated instructions
- [x] Update the MCP integration tests for the merged and renamed tools
- [x] `./mvnw verify`

## Not doing

- [x] ~~Deprecation path for renamed tools~~ — no backward compatibility, by
      project rule and explicit decision. Aliases would double the listing cost of
      exactly the tools this change made leaner
- [x] Commit as `feat(mcp)!:` with a `### Breaking` block naming the four removed
      tool names (`.claude/rules/05-commits.md`)
