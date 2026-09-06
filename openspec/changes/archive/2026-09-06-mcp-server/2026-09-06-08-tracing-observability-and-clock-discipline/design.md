## Context

See `proposal.md` — Why. This section records only the constraints that shape the approach.

**The call path is an adapter, nothing more.**

```
MCP client                POST /mcp   Authorization: Bearer as_<prefix>_<secret>
  → SecurityFilterChain — ApiTokenAuthenticationFilter → StudioPrincipal + grants
  → Spring AI MCP WebMvc stateless transport
  → mcp/          NEW. @McpTool / @McpResource / @McpPrompt beans. Thin.
  → service/**    UNCHANGED. @PreAuthorize, ClusterAccessGuard, AuditService, bulk cap
  → broker/**     Jolokia + Core, NodeCallLimiter, tiered cache
```

The constraints that follow from the existing code:

- **The security and audit model is `ThreadLocal`-based.** `PermissionResolver` reads
  `SecurityContextHolder`; `ActorResolver` reads `RequestContextHolder`. Whatever transport
  and execution model is chosen must leave the tool body on the request thread.
- **The repo is on Jackson 3** (`tools.jackson.databind.*` in `JolokiaJson`,
  `BrokerEventService`, `OpenApiSnapshotTest`). A dependency that drags Jackson 2 onto the
  compile classpath is a finding, not a workaround.
- **`SpaRoutingConfig` registers a `/**` resource handler** returning `index.html` for any
  path that is neither an existing asset nor prefixed `api/` or `actuator/`.
- **`ApiExceptionHandler` is an `@RestControllerAdvice`** and therefore does not see MCP
  tool invocations at all.
- **`ClusterAccessGuard.requireCluster` throws not-found, not access-denied**, deliberately
  and per its javadoc, so a caller without a grant cannot learn whether a cluster id exists.
- **`MessageService` already returns `Attempt<Outcome>`** with `Outcome` sealed as
  `Affected(count, node) | DryRun(count, cap, overCap, node)`, and takes a separate
  `override` boolean for the ADR-0022 bulk cap.
- **Prerequisite.** The cluster-read authorization gap in `CrossNodeAggregator`,
  `PagedListService`, `MetricQueryService`, `AuditQueryService` and `BrokerEventService`
  must have landed first. This design assumes the guard is present in `service/**`; adding
  it in `mcp/` instead would put authorization policy in the adapter layer.

## Goals / Non-Goals

**Goals**

- One authorization model, one credential kind, one audit trail — MCP inherits all three
  rather than paralleling any of them.
- A surface whose discovery and result cost is bounded by a test, not by discipline.
- A guarded-mutation contract that survives a client which ignores every protocol hint.
- Errors a model can act on, without turning a deliberate information-hiding property into
  an enumeration oracle.

**Non-Goals**

- No `resources/subscribe` or `listChanged` notifications. SSE serves the UI; an MCP client
  polls. Declaring an unimplemented capability is worse than not having it.
- No Code Mode / sandboxed `execute_code`. It is the only pattern that cuts both schema and
  response cost by ~98%, but it needs real isolation. Revisit if the tool count grows.
- No host-side tool search or deferred loading — a host concern, not on the wire, and at
  ~12 tools we are far below the 30–50 threshold where it pays.
- No session features (sampling, elicitation, server→client progress).
- No 1:1 mapping of the ~60 REST endpoints, now or later. That is the design, not a phase.

## Decisions

### D1 — Reuse ADR-0039 personal API tokens; add no second credential

`ApiTokenAuthenticationFilter` already sits in the chain after `SecurityContextHolderFilter`,
handles `Authorization: Bearer as_…`, is CSRF-exempt, records `last_used_at`, and intersects
the token's grants with the owner's live grants on every authentication. `SecurityConfig`
gains one line — `/mcp` → `.authenticated()` — and nothing else.

*Alternatives considered.* `spring-boot-starter-oauth2-resource-server`, or Spring AI's own
`mcp-security` `mcpServerApiKey()` module. Both mean a second credential store and a second
authorization model to keep in sync with grants, revocation and audit attribution — exactly
the parallel-path the project's engineering principles forbid. Recorded as ADR-0046.

### D2 — STATELESS transport

`spring.ai.mcp.server.protocol=STATELESS`. No session map, no sticky routing, compatible
with the "Multi-instance HA" roadmap row. It also sidesteps the unbounded-session class of
problem behind CVE-2026-59279 in Spring AI 2.0.0's STREAMABLE transport. We pin **2.0.1**
and never 2.0.0; the pin is the record of why.

*Alternative.* STREAMABLE, which would buy sampling and server-initiated progress. We use
neither, and it would force session affinity onto a system explicitly planning for multiple
instances.

### D3 — `type: SYNC` is load-bearing, not a style preference

Under SYNC the tool body runs on the servlet thread and both `SecurityContextHolder` and
`RequestContextHolder` are intact — virtual threads are on globally, and `ThreadLocal` is
correct there. Under ASYNC the work moves to a Reactor scheduler thread,
`SecurityContextHolder` is empty, `PermissionResolver.can` returns false for everything, and
**every tool answers "no cluster visible"** — a configuration mistake that presents as a
permissions bug. Pin SYNC and let the authorization test be the regression guard. Do not add
`DelegatingSecurityContextExecutor` plumbing to make ASYNC work; it buys nothing here.

### D4 — Split the package by intent, not by controller

`mcp/McpDiagnosticTools` (read) and `mcp/McpTuningTools` (mutating), plus
`McpCatalogResources`, `McpRunbookPrompts`, `McpErrors`, `McpViews`. The split is also the
review boundary: everything in `McpTuningTools` needs the confirm/dry-run contract and
nothing in `McpDiagnosticTools` does, so a reviewer can see at a glance whether a new tool
is in the right file.

### D5 — Collapse families behind a discriminator; reuse the existing enum

`list_resources(kind=…)` rather than six tools. The discriminator is **not invented** —
`service/ResourceKind.java` already enumerates the live-through kinds, with queues on their
own snapshot-backed path. Reuse it; a parallel enum would drift. Same shape for
`activity_log(source=broker_events|audit)`, `trace_request_reply(mode=…)`,
`queue_action(action=…)`, `alert_rule(op=…)`, `studio_setting(op=…)`.

Target ~10–12 tools, flat scalar parameters only, long enums validated server-side rather
than enumerated in the schema. Defaults beat documentation.

### D6 — MCP owns its result projections

Define lean records in `mcp/McpViews.java`. **Do not reuse `web/dto` view records**: they
exist to serve a UI that renders every field, and are shaped by MapStruct mappers for that
purpose. Reusing them silently couples the MCP wire contract to UI needs and drags fields no
model needs. Declare `outputSchema`, return `structuredContent` matching it, and echo JSON
in `content` for older clients.

Result caps live under the existing `artemis-studio:` config root
(`artemis-studio.mcp.default-limit` / `max-limit`) and are deliberately tighter than REST's —
a model pays for every row, and the broker pays for every row it did not need. Mutation
volume is **not** given a second ceiling; it inherits `artemis-studio.safety.bulk-cap`
through `MessageService`.

### D7 — Two independent gates, never conflated

- `confirm` is the **new MCP-layer gate**: proof of intent to act at all. It must equal the
  operation's subject (e.g. the queue name), replacing the UI's typed confirmation which an
  MCP client cannot perform. `dryRun` defaults to `true`.
- `override` is the **existing gate** (ADR-0022): proof of intent to act on more rows than
  the bulk cap allows.

They answer different questions. `confirm` must never be wired to satisfy `override`; expose
`override` as its own argument defaulting to `false`, and let a capped run come back as a
fixable error naming the cap and the count.

Protocol annotations (`readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`)
are set honestly, but they are **hints a client may ignore**. The real gates are
`@PreAuthorize`, `ClusterAccessGuard`, `confirm`, and the bulk cap.

### D8 — The error contract, and the shape of a denial

Map explicitly in `mcp/McpErrors.java`, since `ApiExceptionHandler` does not apply:

- Execution failures (`ConflictException`, bulk-cap exceeded, `BrokerConnectionException`)
  → `isError` result whose message says how to fix it — the cap and the count, or which node
  is unreachable.
- **`NotFoundException` from `ClusterAccessGuard`** → *"no such cluster, or this key has no
  grant on it"*. It must **not** name the missing permission; doing so would turn a
  deliberate information-hiding property into an enumeration oracle.
- **`AccessDeniedException` from a global `@PreAuthorize`** → names the missing permission
  ("this key lacks `settings:write`"). There is no id to hide, and honest capability gating
  (non-negotiable #5) means saying so rather than failing opaquely.
- Protocol faults (unknown tool, malformed argument, unknown resource URI) → JSON-RPC
  `-32602`. Never an empty successful result for a missing resource.
- Never a raw exception message or stack trace — it goes to the model verbatim.

Getting the first two backwards is the failure this design most wants to prevent, so it gets
its own test rather than relying on review.

### D9 — `/mcp` is excluded from the SPA fallback regardless of routing order

The MCP transport registers a functional `RouterFunction`; `RouterFunctionMapping`
(order −1) should outrank the resource `SimpleUrlHandlerMapping`. That is verified, not
assumed. Independently, add `mcp` to `SpaRoutingConfig`'s exclusion list alongside `api/` and
`actuator/`: a `GET /mcp` probe — which clients and humans do make — would otherwise return
`index.html` with a `200`, which is the exact failure that class's javadoc already names,
turning a routing miss into an unreadable error.

*Fallback if the router function does not win:* mount at `/api/mcp` via
`spring.ai.mcp.server.mcp-endpoint`, which inherits the existing `/api/**` authenticated
rule for free. `/mcp` is the convention hosts expect, so try it first.

### D10 — `/account` is a section stack, and the tokens tab goes away

Build `AccountView.tsx` in the `SettingsView.tsx` section-stack style — the cleanest template
in the codebase — with Identity / Password / API keys / MCP connection. Sections, not tabs.
The menu item is **not** wrapped in `<Can>`: every user has an account.

`admin/TokensPanel.tsx` moves to `account/ApiKeysPanel.tsx` and the admin tab is removed
with no redirect stub, per the project's no-backward-compatibility principle. The move is
also where the grant-selection gap closes: permissions from `GET /api/v1/permissions` ×
a scope picker, offering only what `useMe().grants` shows the user holds. The server already
intersects, so this cannot escalate — but offering a grant that would be discarded is
misleading, so the UI does not.

The connection helper derives the endpoint from `window.location.origin` and highlights the
config block with the **existing** `shiki` dependency, as the config-diff and payload screens
already do. It never prints an existing key — unrecoverable by design — showing a
`<your-api-key>` placeholder unless a value was just minted.

### D11 — Verify three things before building anything (Phase 0)

Each can invalidate the design, and each is cheaper to disprove than to discover:

1. **Security context propagation** — one trivial `@McpTool` with
   `@PreAuthorize("isAuthenticated()")` returning the authenticated name, called with a real
   `as_` token, must return the username rather than `anonymousUser`.
   *If it fails:* a `TransportContextExtractor` carrying the `Authorization` header into
   `McpTransportContext`, plus an explicit context set in a small `McpSecurity` helper
   wrapping each tool body. One class, no design change.
2. **Jackson 2 must not appear** — `./mvnw dependency:tree -Dincludes='com.fasterxml.jackson*'`
   is the first command after adding the dependency.
3. **`/mcp` routing and the SPA fallback** — per D9.

## Risks / Trade-offs

- **ASYNC or a non-servlet thread silently empties the security context** → D3 pins SYNC;
  `McpAuthorizationIntegrationTest` fails loudly if it ever changes.
- **Jackson 2 arrives transitively onto a Jackson-3 classpath** → checked as the first
  command in Phase 0; if it lands, it is raised as an ADR-level finding, not worked around.
- **The surface rots back into a REST dump as tools accumulate** → `McpToolSchemaBudgetTest`
  makes the budget a build failure: no single tool over ~200 tokens, the whole listing under
  ~2k.
- **A well-meaning "improve the error message" change turns the cluster denial into an
  enumeration oracle** → `McpClusterHidingTest` asserts the message names no permission and
  does not confirm the id exists.
- **An agent enumerates far faster than a human clicks** → the prerequisite authorization fix
  lands first; MCP does not start until it has.
- **A client ignores every annotation hint** → assumed, not mitigated: the annotations are a
  risk vocabulary for good hosts, and every actual gate is server-side.
- **Burning tokens on a large `tools/list`** → deterministic ordering everywhere, so host
  prompt-cache hits survive.
- **`/mcp` is reachable by any authenticated token whether or not the operator wants an
  agent-reachable endpoint** → see Open Questions.

## Migration Plan

Six slices, each leaving a working product, per the project's grow-in-layers principle. Do
not start a slice before the previous one builds and its tests pass.

1. **Phase 0 probes** — report all three outcomes before writing anything else.
2. **Walking skeleton** — dependency, config, security, SPA exclusion, exactly *one* read
   tool (`cluster_health`), and the protection test. Prove the whole path end to end against
   a real broker with `just dev-up` before adding surface area.
3. **The rest of the read surface** — remaining diagnostic tools, resources, prompts, plus
   the schema-budget and catalogue tests.
4. **The guarded mutations** — `McpTuningTools`, the dry-run/confirm contract, error mapping,
   and the authorization / hiding / dry-run tests. The safety review concentrates here.
5. **Frontend** — `/account`, the panel move, grant selection, the connection helper, type
   regeneration.
6. **Close-out** — ADRs 0045 and 0046, README, CHANGELOG, `docs/architecture.md`, the
   `docs/adr/README.md` index backfill for 0043–0046, `just fmt`, `just verify`.

**Rollback.** Nothing in the backend slices changes existing behaviour: reverting the
`mcp/` package, the two config blocks, the `SecurityConfig` line and the pom entries returns
Studio exactly to its current state — there is no schema change and no data migration. The
frontend slice is the only one that removes a surface (the admin tokens tab); reverting it
restores that tab.

## Open Questions

- **Does the MCP surface need an on/off switch (`artemis-studio.mcp.enabled`)?** Shipping it
  always-on is simpler and it is already authenticated, but an operator who does not want an
  agent-reachable endpoint at all would rather it not be mounted. Decide in slice 2, once we
  know whether the starter can be conditionally disabled cleanly, and record the answer in
  ADR-0045 rather than leaving it implicit. If it ships, it goes in the README `## Run it`
  env-var table. This does not change the specs or the task breakdown either way.
