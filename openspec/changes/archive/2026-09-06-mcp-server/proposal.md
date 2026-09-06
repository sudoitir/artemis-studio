## Why

Everything an operator needs to answer *"why is this cluster unhealthy, and what should I
do about it?"* already exists in Studio — HA state, cross-node resource views, metrics
timeseries, alerting, config diff, request-reply tracing, broker events, audit — but it is
reachable only by a human clicking through six screens. The README roadmap has carried an
**"Artemis MCP"** row under `### Beyond` since v1 planning; this change ships it, so an LLM
agent can triage and tune a cluster on an operator's behalf, under the operator's own
identity and permissions, with every mutation audited exactly as a UI click would be.

The second half of the change is the consequence of the first: personal API keys are the
credential an MCP client presents, and today they live on an admin tab and are minted with
**no grants at all** — a key minted from the UI can do nothing. That has to be fixed for
the MCP surface to be usable by anyone who is not hand-crafting API calls.

## What Changes

**An MCP server at `POST /mcp`, as a capability surface rather than a REST dump.**
~10–12 intent-shaped tools, four resources, and four prompt runbooks — not a 1:1 mapping of
the ~60 existing REST endpoints. Families collapse behind a discriminator argument
(`list_resources(kind=…)`, `activity_log(source=…)`, `queue_action(action=…)`) so schema
cost stays flat. Tools are a thin adapter over the existing `service/**` layer, so
`@PreAuthorize`, `ClusterAccessGuard`, `AuditService` and the bulk cap apply unchanged.

- **Authentication reuses ADR-0039 personal API tokens.** `Authorization: Bearer as_…`,
  the existing `ApiTokenAuthenticationFilter`, the existing grant intersection with the
  live owner. No OAuth2 resource server, no second credential store.
- **Guarded mutations.** Mutating tools default `dryRun=true` and require an explicit
  `confirm` argument to act for real — the protocol-level equivalent of the UI's typed
  confirmation, which an MCP client cannot perform. The bulk-cap `override` (ADR-0022)
  stays a separate, independent argument; the two are never conflated.
- **A stated error contract.** `ApiExceptionHandler` does not apply to tool invocations, so
  errors are mapped explicitly: execution failures become `isError` results carrying a
  message the model can act on; the `ClusterAccessGuard` not-found-rather-than-forbidden
  property is preserved so a key without a grant cannot enumerate cluster ids; protocol
  faults become JSON-RPC errors.
- **A result-size budget that is tested, not aspirational.** Lean MCP-owned projections
  (never the UI's `web/dto` records), low default limits with enforced maxima, bodies
  fetched on demand, deterministic ordering — and a test that fails when `tools/list`
  grows past budget.
- **STATELESS transport, `type: SYNC`.** Stateless keeps multi-instance HA open; SYNC is
  load-bearing because the whole security and audit model is `ThreadLocal`-based.

**A real `/account` page.**

- New top-level `/account` route reached from the user menu: identity, password, API keys,
  and an MCP connection helper that turns a minted key into a working client config.
- API keys **move off** `/admin?tab=tokens`; the admin tab is removed with no redirect stub.
- **Grant selection when minting a key.** The current modal posts `grants: []`, so every
  UI-minted key is powerless today. The new panel offers permission × scope selection,
  limited to what the minting user actually holds.

**Not in this change:** the cluster-read authorization gap in `CrossNodeAggregator`,
`PagedListService`, `MetricQueryService`, `AuditQueryService` and `BrokerEventService` is a
prerequisite bug fix already in flight on `fix/cluster-read-authorization`. MCP must not
start until it has landed, because `list_resources`, `metric_series` and `activity_log` map
straight onto those five and an agent enumerates far faster than a human clicks.

## Capabilities

### New Capabilities

- `mcp-server`: the MCP surface — its primitive catalogue and the intent-shaped design rule
  that governs it, the authentication and authorization contract, the guarded-mutation
  contract (`dryRun` default, `confirm` gate, separate bulk-cap override), the error
  contract including the cluster-existence hiding property, and the result-size discipline.

### Modified Capabilities

- `api-tokens`: token authentication now also admits the MCP endpoint, and minting must
  offer grant selection rather than producing a grant-less token.
- `identity-and-sessions`: a user has an account page for their own identity, password and
  API keys; personal key management is no longer an administration surface.

## Impact

- **New**: `src/main/java/.../mcp/**` (tools, resources, prompts, error mapping, views) and
  its tests; `web/src/account/**`; ADR-0045 (MCP is a capability surface) and ADR-0046 (MCP
  authenticates with existing API tokens).
- **Dependencies**: Spring AI BOM **2.0.1** + `spring-ai-starter-mcp-server-webmvc`. Pinned
  to 2.0.1 and never 2.0.0 (CVE-2026-59279, unbounded session retention in the STREAMABLE
  transport). Must be verified not to drag Jackson 2 onto a Jackson-3-only classpath.
- **Modified**: `pom.xml`, `application.yml`, `SecurityConfig` (`/mcp` → authenticated),
  `SpaRoutingConfig` (exclude `mcp` from the SPA fallback so a `GET /mcp` probe does not
  get `index.html` with a 200), `web/src/router.tsx`, `UserMenu.tsx`, `AdminView.tsx`,
  `web/src/api/client.ts`, regenerated `web/openapi.json` + `schema.d.ts`, `README.md`,
  `CHANGELOG.md`, `docs/architecture.md`, `docs/adr/README.md` (index backfill for
  0043–0046).
- **Deleted**: `web/src/admin/TokensPanel.tsx` (moved to `web/src/account/ApiKeysPanel.tsx`).
- **Not touched**: `pom.xml` / `package.json` version strings — the version is CI-derived
  CalVer (ADR-0042); the `## [Unreleased]` CHANGELOG entry is this change's version bump.
