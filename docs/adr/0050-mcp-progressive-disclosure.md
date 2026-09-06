# ADR-0050: The MCP listing budget scales per tool, and detail moves to a resource

- **Status**: superseded by [ADR-0054](0054-mcp-discovery-is-a-tool-not-a-resource.md)
- **Date**: 2026-09-06
- **Deciders**: Artemis Studio maintainers
- **Extends**: [ADR-0045](0045-mcp-server-is-a-capability-surface.md)

## Context

ADR-0045 made `tools/list` size a build failure, with two ceilings enforced by
`McpToolSchemaBudgetTest`: 200 tokens for any one tool, and 2000 tokens for the
whole listing. The reasoning holds and is not in question — the listing is sent to
a model on every conversation that touches Studio, before it has asked for
anything, and the pressure on it is one-directional because every change adds a
tool or a parameter and none removes one.

The 2000 figure was calibrated against the thirteen tools that existed. Adding
`queue_lifecycle` (ADR-0049) took the listing to 2185, and the fourteenth tool is
not the problem: the listing had no headroom at all, so *any* new capability would
have failed, however lean. A fixed total turns "we enforce leanness" into "the
surface is frozen at thirteen tools", which is a different decision than the one
ADR-0045 made and not one anybody took deliberately.

Meanwhile a large share of what the listing spends is detail a model needs only
*after* it has chosen a tool: enum members spelled out in a schema, and JSON body
shapes for `config` and `rule` parameters. Every conversation pays for them; one
call in a hundred uses them.

## Decision

**Detail moves out of the schemas and into a resource.** `studio://tools` carries
the valid values and JSON body shapes, and the schemas name it. A model fetches it
once, when it has already decided which tool it wants. This is documentation, not
validation: every discriminator is still checked server-side and every rejection
still names the values it would have accepted, so a model that never reads the
resource is inconvenienced, never wrong.

Resources are the right home because a host fetches a resource once and keeps it,
where the tool listing is re-sent every time — the same reasoning that already put
the cluster catalogue and capability ledger in resources rather than tools.

**The total budget becomes an average, not a constant.** The test now enforces:

- **200 tokens for any single tool**, unchanged. No tool may dominate the listing;
  one that needs more must move its detail to `studio://tools`.
- **160 tokens per tool on average**, replacing the flat 2000.

The average keeps the ratchet that ADR-0045 wanted — a tool family that bloats
still fails the build — while letting the surface grow when the product genuinely
gains a capability. Growth costs a new tool's worth of budget and nothing more.

**Tools are not deleted to make room.** A capability the product has is a
capability the MCP surface exposes; trimming the surface to fit a number would make
the product worse to serve a proxy for the thing we actually care about.

## Consequences

The MCP surface can grow with the product. Adding a capability is a normal change
again rather than a negotiation with a constant.

The per-tool ceiling now does most of the work, which is where the real pressure
belongs: a listing of lean tools is fine at any count, and a listing of bloated
ones fails whatever the count.

We accept that the listing grows linearly with tool count. If it ever becomes the
dominant cost, the answer is fewer, better-grouped tools — not a smaller ceiling —
and that will be its own decision.

A model that ignores `studio://tools` gets terser schemas than before and learns
valid values from rejection messages instead. Slightly more round trips in the
worst case; no wrong answers, because server-side validation was always the
authority.

`McpToolSchemaBudgetTest`'s message no longer says "do not raise this ceiling",
because the ceiling is now a function of tool count. It says what it now means:
move detail to the resource, or split the tool.

## Alternatives considered

**Raise the flat total to 2500.** Rejected: it buys three tools and recreates the
same conversation. The problem is the shape of the limit, not its value.

**Drop the `queue_lifecycle` tool and expose lifecycle over HTTP only.** Rejected:
it makes the MCP surface a partial view of the product, which is exactly what
ADR-0045 set out to avoid, and the agent use case for queue lifecycle is a strong
one.

**Collapse existing tool families further to reclaim budget.** Rejected for now:
the families are already discriminated, and merging unrelated verbs behind one tool
to save tokens would make each harder for a model to choose correctly. Tool
selection accuracy is worth more than the tokens.

**Trim every existing tool's descriptions by a few characters each.** Rejected as
the primary fix: it works once, degrades the surface a little, and leaves the next
capability facing the same wall.
