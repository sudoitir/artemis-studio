## 0. Prerequisite — the cluster-read authorization gap must have landed

- [x] 0.1 Confirm `fix/cluster-read-authorization` is merged: `CrossNodeAggregator.queues`, `PagedListService.{addresses,consumers,sessions,connections,producers}`, `MetricQueryService.query`, `AuditQueryService.page` and `BrokerEventService.page` each call `clusterAccess.requireCluster(...)`, and a grant-exercising authorization test exists. **Do not start section 1 before this is true** — `list_resources`, `metric_series` and `activity_log` map straight onto those five

## 1. Phase 0 — de-risk before building (report all three outcomes before section 2)

- [x] 1.1 Add the Spring AI BOM + `spring-ai-starter-mcp-server-webmvc` in a throwaway state and run `./mvnw dependency:tree -Dincludes='com.fasterxml.jackson*'` as the **first** command. Jackson 2 on the compile classpath is an ADR-level finding to raise, not something to work around
- [x] 1.2 Confirm `./mvnw -DskipTests package` still builds with the starter present
- [x] 1.3 Add one trivial `@McpTool` annotated `@PreAuthorize("isAuthenticated()")` returning the authenticated principal's name; call `/mcp` with a real `as_` token. It must return the username, not `anonymousUser` or an NPE. If it fails, fall back to a `TransportContextExtractor` + `McpSecurity` helper (design D11) and say so
- [x] 1.4 Confirm `POST /mcp` reaches the transport's `RouterFunction` and is not swallowed by `SpaRoutingConfig`'s `/**` resource handler. If it is, mount at `/api/mcp` via `spring.ai.mcp.server.mcp-endpoint` instead
- [x] 1.5 Report 1.1–1.4 with their actual output before writing any further code

## 2. Slice 1 — walking skeleton (one tool, proven end to end)

- [x] 2.1 `pom.xml`: `<spring-ai.version>2.0.1</spring-ai.version>` next to the other pinned versions, with a comment in the style of `artemis.version` / `springdoc.version` noting **2.0.1, never 2.0.0** (CVE-2026-59279)
- [x] 2.2 `pom.xml`: import `org.springframework.ai:spring-ai-bom` in `<dependencyManagement>` beside the `testcontainers-bom` import; add `spring-ai-starter-mcp-server-webmvc` with the version from the BOM
- [x] 2.3 `application.yml`: `spring.ai.mcp.server` block — `name`, `version`, `type: SYNC`, `protocol: STATELESS`, `annotation-scanner.enabled: true`, capabilities declaring only `resource` and `prompt`. Comment why SYNC is load-bearing (design D3), not a preference
- [x] 2.4 `application.yml`: `instructions` naming the surface's capabilities — under a host that does tool search rather than dumping `tools/list`, this *is* the discovery index
- [x] 2.5 `application.yml`: `artemis-studio.mcp.default-limit: 25` / `max-limit: 100` under the existing `artemis-studio:` root, matching that block's one-line-why comment style. No second mutation ceiling — that inherits `artemis-studio.safety.bulk-cap`
- [x] 2.6 `config/SecurityConfig.java`: `/mcp` (or `/mcp/**`) → `.authenticated()`, before the `anyRequest().permitAll()` fallback. Nothing else changes
- [x] 2.7 `config/SpaRoutingConfig.java`: add `mcp` to the `api/` / `actuator/` exclusion list, so a `GET /mcp` probe does not get `index.html` with a 200
- [x] 2.8 `mcp/McpViews.java`: the lean projection for `cluster_health` — flat, scalar-heavy, ids not object graphs. **Not** a `web/dto` record
- [x] 2.9 `mcp/McpDiagnosticTools.java`: `cluster_health` only — HA state per node, split-brain verdict, firing alerts, pressure signals. `readOnlyHint: true`, `destructiveHint: false`, `idempotentHint: true`, `openWorldHint: false`. Declare `outputSchema`, return `structuredContent`, echo JSON in `content`
- [x] 2.10 `mcp/McpEndpointProtectionTest`: `POST /mcp` unauthenticated → 401; with a valid `as_` token → 200. Mirror `web/EndpointProtectionTest`'s style
- [x] 2.11 Verify `/mcp` did **not** enter the OpenAPI document — run `web/OpenApiSnapshotTest` and check `git diff web/openapi.json`. If it appears, add `springdoc.paths-to-exclude: /mcp` (the file has none today)
- [ ] 2.12 `just dev-up`, mint a key with `cluster:read`, and prove the whole path: `tools/list` then `tools/call cluster_health` returning real topology. Do not add surface area before this passes
- [x] 2.13 Decide the design's open question — whether `artemis-studio.mcp.enabled` ships — now that we know whether the starter disables cleanly. Record the answer in ADR-0045; if it ships, it goes in the README `## Run it` env table

## 3. Slice 2 — the rest of the read surface

- [x] 3.1 `list_resources(kind, filter, sort, limit)` — reuse `service/ResourceKind.java`; do **not** define a parallel enum. Queues keep their snapshot-backed path
- [x] 3.2 `diagnose_queue` — the composite: depth and trend, consumer count, paused state, slow-consumer verdict, DLQ relationship, recent events. Replaces four screens
- [x] 3.3 `metric_series(metric, subject, window)` — server-bucketed timeseries
- [x] 3.4 `config_diff(clusterId, nodeA, nodeB?)` — classified pointer diff across a node pair (ADR-0043)
- [x] 3.5 `browse_messages` — capped page of headers plus a truncation marker; bodies are a second explicit call, large payloads go back as `resource_link`, never embedded text
- [x] 3.6 `trace_request_reply(mode = flows|stats|expectations)`
- [x] 3.7 `activity_log(source = broker_events|audit)` — two log-shaped reads, one schema
- [x] 3.8 Every list tool: `limit` with the low default, the enforced maximum, deterministic ordering, and an explicit statement when a result was capped
- [x] 3.9 `mcp/McpCatalogResources.java`: `studio://clusters` (catalogue, filtered to the caller), `studio://permissions` (what *this token* can do), `cluster://{clusterId}/topology`, `cluster://{clusterId}/capabilities`
- [x] 3.10 `cluster://{clusterId}/capabilities` includes, per non-negotiable #5, the exact `broker.xml` snippet for each capability the connection cannot reach. No silently absent capability
- [x] 3.11 `mcp/McpRunbookPrompts.java`: `triage_cluster`, `investigate_queue`, `before_you_purge`, `tune_scrape_load`. Stateless, orchestrating only — `before_you_purge` prescribes dry-run → verify consumers → sample → **ask the human**, never a confirmed run
- [x] 3.12 `mcp/McpToolSchemaBudgetTest`: `tools/list` size (`chars/4`) under budget — no single tool over ~200 tokens, whole listing under ~2k. This is the test that stops the surface rotting back into a REST dump
- [x] 3.13 `mcp/McpToolCatalogueTest`: `tools/list` ordering is deterministic and does not vary by connection state

## 4. Slice 3 — the guarded mutations (the safety review concentrates here)

- [x] 4.1 `mcp/McpTuningTools.java`: `queue_action(action = move|retry|delete|expire|purge)` — `dryRun` defaults **true**; a real run requires `confirm` to equal the queue name; `destructiveHint: true`
- [x] 4.2 `queue_action`: expose `override` as its own argument defaulting to `false`. `confirm` must **never** be wired to satisfy the bulk-cap `override` — they answer different questions (design D7)
- [x] 4.3 `send_message` — `dryRun` defaults true; mutating but additive, so `destructiveHint: false`
- [x] 4.4 `alert_rule(op = list|create|update|delete)`
- [x] 4.5 `studio_setting(op = get|set)` — scrape cadence, per-node rate limit, bulk cap; guarded by `settings:write`
- [x] 4.6 `mcp/McpErrors.java`: execution failures → `isError` with a message naming the fix (`ConflictException`; bulk-cap exceeded stating the cap, the count and that `override` exists; `BrokerConnectionException` naming the node and that the cluster may be down)
- [x] 4.7 `McpErrors`: `NotFoundException` from `ClusterAccessGuard` → *"no such cluster, or this key has no grant on it"*, **naming no permission**. `AccessDeniedException` from a global `@PreAuthorize` → names the missing permission. Getting these backwards turns information-hiding into an enumeration oracle
- [x] 4.8 `McpErrors`: protocol faults (unknown tool, malformed argument, unknown resource URI) → JSON-RPC `-32602`. Never an empty `contents` for a missing resource; never a raw exception message or stack trace
- [x] 4.9 `mcp/McpAuthorizationIntegrationTest`: a token whose grants lack `queue:purge` gets an `isError` from `queue_action(action=purge)` — not a 500, not a success. The grant intersection holds end to end
- [x] 4.10 `mcp/McpDryRunIntegrationTest`: default invocation mutates nothing and returns an estimate; a real run without a matching `confirm` is refused; the audit row exists with `dry_run = true`
- [x] 4.11 `mcp/McpClusterHidingTest`: a token with no grant on a cluster gets the not-found-shaped message from a cluster-addressed tool — asserting it names no permission and does not confirm the id exists. Guards §4.7 against a well-meaning "improve the error message" regression
- [x] 4.12 `mcp/McpErrorMappingTest`: each mapped exception produces `isError: true` with a message naming the fix; unknown tool → `-32602`

## 5. Slice 4 — frontend: the `/account` page

- [x] 5.1 `web/src/router.tsx`: top-level `/account` route beside `/admin`, following the existing hand-written `createRoute({getParentRoute, path, component, errorComponent})` pattern. No `validateSearch` — this is a section stack, not tabs
- [x] 5.2 `web/src/account/AccountView.tsx`: the `SettingsView.tsx` section-stack style — `<Stack gap="xl" maw={640}>` of `<div><Title order={3}/><Text size="sm" c="dimmed" mb="sm"/><Component/></div>` separated by `<Divider/>`. Sections: Identity (from `useMe()`) → Password (link to `/change-password`) → API keys → MCP connection
- [x] 5.3 `web/src/app/UserMenu.tsx`: an "Account" `Menu.Item` above "Change password", **not** wrapped in `<Can>` — every user has an account
- [x] 5.4 Move `web/src/admin/TokensPanel.tsx` → `web/src/account/ApiKeysPanel.tsx`
- [x] 5.5 `web/src/admin/AdminView.tsx`: remove the `tokens` tab (tab list and panel). No redirect stub
- [x] 5.6 `web/src/api/client.ts`: a permissions hook over `GET /api/v1/permissions`, and grant-carrying token creation — new hooks under a `// ── … ──` banner, query keys in `keys`, `invalidateQueries` on mutation success
- [x] 5.7 `ApiKeysPanel`: grant selection — permission checkboxes × a scope picker (Global, or a cluster from `useClusters()`), posting real `grants` to `POST /api/v1/tokens`. Offer only what `useMe().grants` shows the user holds, so the interaction is not misleading. The server already intersects, so this cannot escalate
- [x] 5.8 `ApiKeysPanel`: keep the show-once minted-value flow exactly as it is (`Alert` + readonly `TextInput` + `CopyButton`)
- [x] 5.9 `web/src/account/McpConnectionPanel.tsx`: endpoint from `window.location.origin` plus a copyable client config, highlighted with the **existing** `shiki` dependency as the config-diff and payload screens do. Never print an existing key — `<your-api-key>` placeholder, or the just-minted value when one is in hand
- [x] 5.10 No raw colours (semantic `--as-*` only), logical CSS properties only, `branding.ts` for the product name, no hand-written DTO interfaces (alias from `Schemas[...]`)
- [x] 5.11 `web/src/account/AccountView.test.tsx` and `ApiKeysPanel.test.tsx` — MSW-backed, per ADR-0024: the page renders, grant selection posts real grants, the minted value is shown once
- [x] 5.12 `npm run gen:api` in `web/`; commit both `web/openapi.json` and `web/src/api/schema.d.ts` — CI fails on a stale snapshot via `git diff --exit-code`

## 6. Close-out

- [x] 6.1 `docs/adr/0045-mcp-server-is-a-capability-surface.md` — why ~12 intent-shaped primitives rather than a REST mirror; the tools/resources/prompts split; the token budget and how it is enforced; STATELESS over STREAMABLE; the guarded-mutation contract; the error contract; the 2.0.1 pin and the 2.0.0 CVE; the 2.13 enable-flag decision. Nygard style per `docs/adr/000-template.md`, including the consequences that hurt
- [x] 6.2 `docs/adr/0046-mcp-authenticates-with-existing-api-tokens.md` — why ADR-0039 tokens rather than an OAuth2 resource server or `mcpServerApiKey()`; grants stay the single authorization model; the consequence that a token is bounded by its owner's live grants and revocation is immediate
- [x] 6.3 `docs/adr/README.md`: backfill the index table, which stops at 0042 — add 0043, 0044, 0045, 0046
- [x] 6.4 `docs/architecture.md`: the MCP adapter in the ASCII component diagram (a new inbound arrow beside the browser), plus a short `## MCP surface` section after `## Safety and audit` explaining that MCP inherits those guarantees rather than restating them
- [x] 6.5 `README.md`: delete the `| [ ] | Artemis MCP |` row from `### Beyond`; extend `## Status`; add `MCP | Spring AI 2.0.1, stateless Streamable HTTP at /mcp | ADR-0045` to the Stack table
- [x] 6.6 `README.md`: new `## MCP` section after `## Run it` — what the surface is for in two sentences; how to get a key (sign in → avatar menu → Account → API keys → New key → choose permissions → copy once); the endpoint and `Authorization: Bearer as_...`; a copy-paste client config; a short tool/resource/prompt table (the ADR is authoritative); the safety contract stated plainly (a key never exceeds its owner, mutations dry-run by default and need an explicit confirm, everything is audited under the owner with the key's name); and a `curl` `tools/list` smoke test
- [x] 6.7 `CHANGELOG.md` under `## [Unreleased]` → `### Added`, written for someone upgrading: the MCP server and how to get a key; the `/account` page; grant selection when minting a key (previously UI-minted keys were powerless). Under `### Changed`: API keys moved off Administration — a user who bookmarked `/admin?tab=tokens` needs to know where it went. **This is the version bump**; `pom.xml` and `web/package.json` are not touched (ADR-0042)
- [x] 6.8 `just fmt` — Palantir/Spotless + `eslint --fix`. Never hand-fight the formatter
- [x] 6.9 `just verify` — the full CI-equivalent gate

## 7. End-to-end verification against a real broker

- [ ] 7.1 `just dev-up`; sign in; `/account`; mint a key with `cluster:read` + `message:read`
- [ ] 7.2 `tools/list` via the README `curl` — confirm the tool set and its size
- [ ] 7.3 `tools/call cluster_health` — real topology comes back
- [ ] 7.4 `tools/call queue_action(action=purge)` with defaults — dry-runs, returns an estimate, touches nothing, writes an audit row with `dry_run = true`
- [ ] 7.5 Repeat 7.4 with a key lacking `queue:purge` — a clean `isError`, not a stack trace
- [ ] 7.6 Repeat 7.3 with a key holding no grant on that cluster — the not-found-shaped message, naming no permission
- [ ] 7.7 `/clusters/{id}/audit` in the UI shows the owner's username with the key's name attached (`user [token: name]`)
