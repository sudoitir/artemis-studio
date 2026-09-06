# ADR-0054: MCP discovery is a tool, and the surface is generated from one catalogue

- **Status**: accepted
- **Date**: 2026-09-06
- **Deciders**: Artemis Studio maintainers
- **Extends**: [ADR-0045](0045-mcp-server-is-a-capability-surface.md)
- **Supersedes**: [ADR-0050](0050-mcp-progressive-disclosure.md)

## Context

ADR-0050 moved enum members and JSON body shapes out of the tool schemas and into
the `studio://tools` resource, on the reasoning that "a host fetches a resource
once and keeps it, where the tool listing is re-sent every time". The premise is
true of hosts that read resources. It is silent about the ones that do not.

`resources` is an optional server capability in the MCP specification, and nothing
in the protocol obliges a client to call `resources/read` — ever. Tools are the one
part of MCP every host implements, because they are the point of the protocol.
ADR-0050 therefore moved the detail a model needs onto the least reliable delivery
channel MCP has, and assumed it would arrive. Where it does not, ADR-0050 did not
make discovery progressive; it made it *absent*, and the model is left inferring
valid values by triggering rejections.

That is also a non-negotiable #5 problem wearing a different hat. A model that
cannot discover an operation, and is never told where to look, concludes the
operation does not exist.

Two further facts, verified rather than assumed, bound the space:

- **Dynamic disclosure is not available.** `notifications/tools/list_changed` is
  optional in the specification ("*should* send"), with no client obligation to
  re-fetch. More decisively, `addTool`/`removeTool`/`listTools` in MCP SDK 2.0.0
  are **server-global** — there is no per-session tool view and no filter
  extension point in `mcp-core`. Registering a tool because one model asked would
  expose it to every connected client. This holds in stateful mode too, so it is
  not something `protocol: STATELESS` is costing us.
- **`destructiveHint` is host-actionable and per tool.** A host gates a whole tool
  on one flag. Any grouping that puts a read behind a tool that can purge makes
  every read prompt the operator, which is how an operator is trained to
  reflex-approve the purge.

Meanwhile the server `instructions` block — the one text a tool-searching host is
guaranteed to read — was hand-maintained in `application.yml` and had already
drifted: it omitted `queue_lifecycle` and `message_body`, and never named
`studio://tools`. A hand-kept duplicate of the surface asserts, by omission, that
capabilities the product has do not exist.

## Decision

**Discovery is a tool.** `studio_help(topic?)` costs roughly 35 tokens in the
listing and returns the operation index with no argument, or one tool's enum
values, body shapes and semantics with a topic. It works on every host, and its
result lands in the model's context, which is where the detail is needed.

`studio://tools` remains, generated from the same source, as a free mirror for
hosts that do read resources. It is no longer the primary channel and nothing
depends on it being read.

**Because the channel is now reliable, the schemas get leaner than ADR-0050 dared.**
Descriptions that existed to hedge against a fetch that might not happen — spelled
defaults, type codes, "see studio://tools" pointers — come out. This is not a
token trade: the help tool costs ~35 and licenses stripping several hundred, so
the listing is smaller than it was before the tool was added.

**Rejections carry discovery.** Every `-32602` that names its valid values also
names `studio_help`, so a wrong guess becomes a recovery rather than a retry loop.

**Grouping requires two conditions, both binding.** Operations merge into one tool
only when they share:

1. **One honest annotation posture.** Posture is a hard ceiling, for the
   `destructiveHint` reason above.
2. **One target and argument core.** Posture alone is not sufficient. All nine
   read tools share a posture, and merging them would produce an `op` union across
   unrelated return shapes — a schema unusable without fetching detail first,
   which makes discovery mandatory and is forbidden by the `mcp-server` spec.

The second condition is what refuses `message_action` + `queue_lifecycle` despite
identical postures: messages *in* a queue and the queue *itself* are different
targets, and the union would be thirteen operations across two disjoint argument
clusters, on the most dangerous tool in the product, to save about seventy tokens.

Applied to the existing surface this licenses exactly two merges and one rename:

- `cluster_health` + `diagnose_queue` → **`diagnose(clusterId, queue?)`**, scoped
  by an optional queue, the pattern `metric_series` already uses.
- `browse_messages` + `message_body` → **`browse_messages(..., messageId?)`**;
  the body was always the drill-down from a browse row, on the same target.
- `queue_action` → **`message_action`**. It acts on messages, not the queue, and
  it sat next to `queue_lifecycle`, which acts on the queue. That was the most
  confusable pair on the surface and the names inverted the distinction.

**One catalogue generates everything.** `McpToolCatalog` is the single source for
`studio_help`, `studio://tools` and the server `instructions` block. The block is
no longer written by hand in `application.yml`.

**The budget ratchets down.** Per-tool 200 → 175, average 160 → 135, calibrated
against a measured run rather than estimated. ADR-0050's ceilings were set for
schemas that had to hedge; they no longer do, and leaving the slack in place would
bank the win as headroom for future bloat.

Measured, with `McpToolSchemaBudgetTest`: the listing was **2115 tokens across 14
tools (151 each)** before this change and is **1696 across 13 (130 each)** after —
420 tokens off every conversation that touches Studio, while *gaining* a discovery
tool. `message_action` is the largest single tool at 169; its nine parameters and
its five named verbs are the selection signal a model matches on, so the ceiling is
set above it rather than trading accuracy for a rounder number.

`McpToolSchemaBudgetTest` additionally enforces that a tool declaring
`readOnlyHint = true` reaches no mutating service, and that every registered tool
appears in the catalogue — so the drift that happened in `application.yml` becomes
a build failure, which is ADR-0045's own argument applied to the thing that
actually drifted.

## Consequences

Detail reaches the model on every host, not only on hosts that read resources.
That was the point.

The listing is a fifth smaller than before despite gaining a tool, and the surface stays
navigable as the roadmap adds capabilities: a model reads one `studio_help` result
rather than scanning thirty schemas.

Tool names change and no compatibility path is offered. `queue_action`,
`cluster_health`, `diagnose_queue` and `message_body` are gone; an agent
configuration naming them breaks and must be updated. This is a deliberate
application of the project's no-backward-compatibility rule — a rename with a
deprecated alias would double the listing cost of exactly the tools we just made
leaner, which is the opposite of the decision.

`studio://tools` is now a mirror. If it is ever the only thing a host reads, it
still answers completely; if it is never read, nothing is lost.

Adding a capability now has one more obligation: register it in the catalogue.
The build fails if that is skipped, which is cheaper than the alternative that
already happened.

## Alternatives considered

**Keep detail in the resource and do nothing (ADR-0050 as-is).** Rejected: it
bets the usability of the whole surface on an optional protocol feature, and the
failure mode is silent.

**Collapse to five or six `op`-discriminated domain tools.** Rejected on both
conditions above. It breaks posture honesty, and its argument schemas are unions
across disjoint shapes, which makes reading the detail resource a precondition for
any call rather than an optimisation.

**Filter `tools/list` per API key.** The only dynamic-shaped thing this SDK can
express, since the listing is served on the servlet thread with the security
context available. Rejected: it hides capabilities the product has behind the
caller's permissions, which is non-negotiable #5 head-on. `studio://permissions`
already tells a model what it may do without lying about what exists.

**Move off `protocol: STATELESS` to buy `list_changed`.** Rejected: it costs
multi-instance HA and the session posture that pins spring-ai 2.0.1, and buys a
mechanism that is optional in the specification and, because the SDK tool table is
global, could not deliver per-session disclosure anyway.
