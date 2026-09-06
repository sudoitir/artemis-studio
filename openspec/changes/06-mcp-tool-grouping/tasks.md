# Tasks

**Placeholder.** This change is proposed, not designed. The list below is the
*shape* of the work, not a plan — `proposal.md` says the brainstorm and the
protocol/library check come first, and that this file is rewritten to match what
they decide. Do not work through it as written.

## Before anything else

- [ ] Brainstorm the surface shape (use the brainstorming skill). Answer D-Q1
      through D-Q6 in `design.md`; record the answers there
- [ ] Verify through `ctx7` and the library's own types what the MCP specification
      guarantees about `tools/list_changed`, and what the Spring AI MCP version
      this project depends on actually implements. Do not carry either from memory
- [ ] Check what the hosts this project cares about do with a changed tool list
- [ ] Write the ADR recording the chosen shape (extends 0045 and 0050)
- [ ] **Rewrite this task list** to match the decision, then implement

## Shape of the work, once decided

- [ ] Restructure the `@McpTool` surface in `mcp/` to the chosen grouping
- [ ] Extend or replace `McpToolDetail` / `studio://tools` for the chosen
      discovery route
- [ ] Update `McpToolSchemaBudgetTest` to enforce the new shape's budget
- [ ] Update the MCP integration tests for any renamed or regrouped tool
- [ ] Decide and implement the deprecation path for renamed tools, if any
- [ ] Commit message — `feat(mcp)!:` for any tool rename; a rename is a breaking change for agents (.claude/rules/05-commits.md)
      and needs a `### Breaking` block
