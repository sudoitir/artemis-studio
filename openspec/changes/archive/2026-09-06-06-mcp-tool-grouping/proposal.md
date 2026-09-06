## Why

The MCP surface is 14 tools and ~2100 tokens of `tools/list`, re-sent to a model
on every conversation that touches Studio before it has asked for anything.
ADR-0050 made that ceiling scale per tool so a new capability is no longer blocked
by a constant, and moved enum members and JSON body shapes into a
`studio://tools` resource. That bought room; it did not answer the shape question.

The roadmap has at least four more changes that each add tools — connection and
consumer control, message replay, divert and bridge management, desired-state
drift. On the current trajectory the listing roughly doubles, and at that size two
things start to hurt that do not hurt today:

1. **Cost.** The listing is a fixed tax on every conversation, including the ones
   that never touch Studio's mutating surface at all.
2. **Selection accuracy**, which matters more. A model choosing between thirty
   flat, similarly-named tools picks worse than one choosing between six domains
   and then an operation within one. Wrong tool selection on a surface that can
   destroy a queue is a safety property, not just an ergonomic one.

This change decides the shape before the surface gets there, rather than after.

## What Changes

**Nothing is implemented by this proposal.** It exists to be brainstormed and
designed properly in its own session, because the answer depends on facts about
the MCP protocol and the Spring AI MCP library that must be checked rather than
assumed.

The question to answer: **how should Studio group its MCP tools so a model sees a
small surface and expands only what it needs — without ever concluding that a
capability does not exist?**

The candidate shapes, none of them chosen yet:

- **Static domain grouping.** Four to six entry-point tools (`queues`,
  `messages`, `alerts`, `diagnostics`, `admin`), each discriminated by an `op`
  argument, with per-op detail in `studio://tools`. Honest and stateless; costs
  schema expressiveness, because one tool's arguments become a union across its
  operations.
- **Dynamic tool disclosure.** Expose a small set, and register more at runtime as
  the model works, announcing each change with
  `notifications/tools/list_changed`. Smallest listing; depends on host support
  for re-fetching, and its failure mode is a model silently concluding a
  capability is absent — which collides directly with non-negotiable #5.
- **A capability graph as a resource.** Keep tools flat but publish a
  navigable graph of domains, operations and their relationships as a resource
  the model reads on demand, so discovery is rich while the listing stays lean.
- **Some combination**, most likely static grouping plus the graph resource.

**Whatever is chosen must not remove a capability from the surface to fit a
number.** That constraint is inherited from ADR-0050 and is not up for
re-litigation here.

## Impact

- Specs: `mcp-server` gains requirements about surface shape and discovery.
- ADRs: a new one, extending ADR-0045 and ADR-0050, recording the chosen shape.
- Likely touches every `@McpTool` in `mcp/`, plus `McpToolDetail`,
  `McpToolSchemaBudgetTest`, and the MCP integration tests.
- **Not in scope:** removing any capability, and changing what the tools do. This
  is about how they are presented and discovered.

## How this change must be applied

This is deliberately a thin proposal. When it is picked up:

1. **Brainstorm first** — use the brainstorming skill before any design is
   settled. The shape is a genuine open question with real trade-offs, and the
   wrong call is expensive to reverse once agents depend on tool names.
2. **Check the protocol and the library, do not assume.** What the MCP
   specification actually guarantees about `tools/list_changed`, and what the
   Spring AI MCP version this project uses actually supports, are facts to verify
   through `ctx7` and the library's own types — the project has been bitten by
   stale training data before.
3. **Then rewrite `tasks.md`** to match what was decided. The task list below is a
   placeholder for the shape of the work, not a plan.
4. **Then implement.**

An out-of-date task list is a worse outcome than an edited one; revise this file,
`design.md` and the spec deltas together whenever the discussion changes the
answer.
