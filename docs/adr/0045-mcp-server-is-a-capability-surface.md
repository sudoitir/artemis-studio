# ADR-0045: The MCP server is a capability surface, not a REST mirror

- **Status**: accepted
- **Date**: 2026-09-06
- **Deciders**: Mahdi Amirabdollahi

## Context

Studio's REST API has around sixty endpoints. The obvious way to expose it to an
assistant is to generate one MCP tool per endpoint, and it is the wrong one for
reasons that have nothing to do with taste.

`tools/list` is sent to the model on **every** conversation that touches Studio,
before it has asked anything. Sixty tool schemas is tens of thousands of tokens
of permanent tax, and the pressure on that listing is one-directional: every
future change adds a tool or a parameter and none removes one. Worse, a
REST-shaped listing describes *endpoints* — `GET /clusters/{id}/queues`,
`GET /clusters/{id}/consumers`, `GET /clusters/{id}/sessions` — while a model
asking "why is this queue backed up" needs *one* answer assembled from four of
them. A per-endpoint surface makes the model do the assembly, badly, at four
round trips a question.

The mutating half raises a different problem. Studio's destructive operations are
already guarded (`?dryRun=true`, ADR-0022's bulk cap, typed confirmation in the
UI), but every one of those guards assumes a human at the other end who typed the
queue name. A model produces `dryRun: false` as readily as any other token, and
the queue name it purges may be one it inferred rather than read.

## Decision

**We will expose ~13 intent-shaped tools, not one per endpoint**, split across
three MCP primitives by what each is for:

- **Tools** are actions and questions: `cluster_health`, `list_resources`,
  `diagnose_queue`, `metric_series`, `config_diff`, `browse_messages`,
  `message_body`, `trace_request_reply`, `activity_log`, `queue_action`,
  `send_message`, `alert_rule`, `studio_setting`.
- **Resources** are context a model reads to orient itself, not actions:
  `studio://clusters`, `studio://permissions`, `cluster://{id}/topology`,
  `cluster://{id}/capabilities`.
- **Prompts** are runbooks: `triage_cluster`, `investigate_queue`,
  `before_you_purge`, `tune_scrape_load`.

Families collapse behind a **discriminator argument** rather than becoming
separate tools — `list_resources(kind=…)`, `activity_log(source=…)`,
`trace_request_reply(mode=…)`, `queue_action(action=…)`, `alert_rule(op=…)`,
`studio_setting(op=…)`. Discriminator values are validated server-side and named
in the rejection message rather than spelled out as a JSON-schema enum, because
an enum is paid for on every listing while a rejection costs one round trip.

**The token budget is a build failure, not a review note.**
`McpToolSchemaBudgetTest` estimates the listing at `chars/4` and fails the build
above 2000 tokens total or 200 tokens for any one tool. Review does not catch
surface creep, because each individual addition looks reasonable. When it fails,
the fix is a shorter description or a collapsed family — never a raised ceiling.

**MCP owns its own projections** (`mcp/McpViews.java`). The `web/dto` records are
shaped for a React grid: nested objects, ids alongside their resolved names,
fields a table renders and a model does not read. Reusing them would couple the
two surfaces so that a UI change silently changes the model's context window.

**Mutations carry two independent gates, and they are never conflated:**

- `dryRun` defaults to **true**, and a real destructive run additionally
  requires `confirm` to equal the subject's own name. This gate exists because
  the caller is a model; an exact-name match is something it cannot supply
  without having read the name.
- `override` is the pre-existing ADR-0022 bulk-cap escape, defaults to `false`,
  and answers a different question — "yes, this many messages really is
  intended". A caller that meant to purge one queue has said nothing about
  whether 400 000 messages is a surprise.

**The error contract has one rule with two halves** (`mcp/McpErrors.java`).
Execution failures are *results* — `isError: true` with a message naming the fix,
because that is something the model can act on. Only a malformed call is a
JSON-RPC `-32602`. And a denial's shape depends on whether there is an id to
hide: the `NotFoundException` from `ClusterAccessGuard` becomes *"No such
cluster, or this key has no grant on it"*, naming **no** permission and not
echoing the id, while an `AccessDeniedException` from a global `@PreAuthorize`
does name the permission (non-negotiable #5). Getting those two backwards turns
deliberate information-hiding into an enumeration oracle;
`McpClusterHidingTest` guards it.

**Transport**: Spring AI `spring-ai-starter-mcp-server-webmvc`, pinned to
**2.0.1, never 2.0.0** — that release's STREAMABLE transport retained sessions
without bound (CVE-2026-59279). `protocol: STATELESS`, so there is no session
store to leak, and `type: SYNC`, which is load-bearing rather than a preference:
`PermissionResolver` reads `SecurityContextHolder` and `ActorResolver` reads
`RequestContextHolder`, both `ThreadLocal`. Under ASYNC the tool body would run
on a Reactor scheduler thread with an empty context, every permission check would
return false, and *every* tool would answer "no cluster visible" — a
configuration mistake that presents as a permissions bug.

**No `artemis-studio.mcp.enabled` flag ships.** The starter already publishes
`spring.ai.mcp.server.enabled`; a second switch would be two sources of truth for
one thing.

## Consequences

- The listing costs roughly 2000 tokens, and the budget test says so out loud.
  It is currently close to the ceiling: the next tool added will fail the build
  until something else gets shorter. That is the intent, and it will be
  irritating exactly when it should be.
- Some parameter descriptions are terser than a human reader would like, and a
  few (`filter`, `targetQueue`) carry none at all because the tool description
  says it once instead of nine times. Discoverability leans on
  `spring.ai.mcp.server.instructions` and on the rejection messages.
- `alert_rule` takes its rule body as one JSON argument rather than ten flat
  scalars. This departs from the flat-scalar convention used everywhere else in
  the surface — ten scalars cost more of the budget than the whole read surface's
  filters put together — and it is a place a model will get the shape wrong more
  often than it would with named parameters.
- Every new REST endpoint now needs a deliberate answer to "does this belong in
  MCP", and the default answer is no. That is friction on purpose, but it means
  the two surfaces will drift and someone has to keep noticing.
- `queue_action` is one tool covering five operations with different argument
  shapes, so its schema cannot express "targetQueue is required when
  action=move". That check lives in `MessageService` and surfaces as a rejection.
- A model that ignores the `confirm` gate simply cannot mutate: it gets a
  `-32602` naming the exact string required. A model that *can* read the queue
  name can also supply it, so this stops accidents, not a determined caller with
  a valid key. The permission model, not this gate, is what bounds the latter.

## Alternatives considered

**One tool per REST endpoint, generated from the OpenAPI document.** Free to
build and free to maintain, and it is what most "add MCP to your API" tooling
does. Rejected on the token budget alone, before considering that it makes the
model assemble every real question from four calls.

**Fewer, larger tools — one `studio_query` taking a query language.** Smallest
possible listing, but it moves the entire surface into a string the model has to
get right with no schema help, and every mistake is a round trip. The
discriminator collapse gets most of the size win while keeping arguments typed.

**Reusing `web/dto` records as tool output.** Zero new code. Rejected because it
couples the model's context window to the React grid's shape: a column added for
the UI would silently widen every tool result.

**A separate `confirm`-free destructive path for trusted callers.** Considered
and dropped — "trusted" would have to mean a flag on a key, which is a second
authorization model beside grants (ADR-0046 exists to avoid exactly that).
