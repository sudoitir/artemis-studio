## Context

Tokens are `as_<prefix>_<secret>`, SHA-256 hashed, looked up by prefix, with grants intersected
with the owner's live grants on every request (ADR-0039). `ApiTokenService.authenticate` builds a
`StudioPrincipal` carrying only the token's name; `BearerAuthenticationFilter` runs it through
`ApiTokenIdentity`. `last_used_at` is buffered in memory and flushed each minute. The permission
`token:admin` is declared by the module but checked nowhere. `expires_at` is optional.

The MCP server is Spring AI's stateless sync server (mcp-core 2.0, spring-ai 2.0.1). Built-in
tools are `@McpTool` beans picked up by the annotation scanner; plugin tools are added at runtime
by `McpPluginBridge`. There is no Studio code on the path of every call: `tools/list` is built
inside `McpStatelessAsyncServer`, and `McpServerInstructions` writes one instruction text naming
every tool for everyone. Mutations are audited only because tools call the same services as REST;
reads are not audited.

Installation settings are `SettingDef`s of kind `DURATION`, `INT` or `CRON` in `studio_setting`,
rendered automatically in Operational configuration (ADR-0047).

## Goals / Non-Goals

**Goals**
- Rotation with overlap, a live lifetime cap, request limits, hourly usage counters, an admin
  inventory with revoke and stale flags.
- One gate every MCP request passes through, applying the allow-list and global read-only and
  auditing every call.

**Non-Goals**
- Multi-instance limit or usage state (single instance, ADR-0037).
- An installation-wide audit view; MCP rows for cluster-less tools are stored but not browsable.
- Filtering runbook prompts in read-only mode (prompts never act).

## Decisions

### D1: The lifetime cap is evaluated live
Effective expiry is `min(expires_at, created_at + apitokens.max-lifetime)`, computed in
`authenticate` and in every token view. Lowering the cap shortens every token at once, with no
sweep; raising it never extends a token past its own `expires_at`. `expires_at` becomes required
(`NOT NULL`, backfilled to `created_at + 90 days`); a mint beyond `now + cap` is a 400 naming the
latest allowed expiry. Default cap 90 days (user decision).
Alternative rejected: clamping stored expiries when the cap changes. A second write path and a
race with minting, for the same result.

### D2: Rotation keeps the previous secret in three columns
`api_token` gains `previous_prefix`, `previous_token_hash`, `previous_valid_until`. Rotation moves
the current prefix and hash there, sets `previous_valid_until = now + apitokens.rotation-overlap`
and writes a new prefix and hash. Grants, allow-list, `created_at` and `expires_at` stay, so
rotation never extends a lifetime. Lookup is by `prefix` or `previous_prefix` (partial unique
index). The previous columns are kept after the window so a late use of the old secret is
recognised: 401 and a `TOKEN_REJECTED` audit row naming the owner and token. A revoked or expired
token cannot be rotated. One overlap per token: rotating again replaces the previous secret.
Alternative rejected: a child table of secrets. More joins for a case that needs exactly two.

### D3: Request limits are a filter on token requests only
`TokenRequestLimitFilter` (`OncePerRequestFilter`, after the security chain like
`FeatureDisabledFilter`) acts only when the principal came from a token, including `/mcp`.
Session traffic is never throttled: the UI polls. Per token: a fixed one-minute window
(`apitokens.token-requests-per-minute`, 600) and an in-flight cap (`apitokens.token-concurrency`,
8). Per user across all their tokens: `apitokens.user-requests-per-minute`, 1200. Each token keeps
its own window, so a flooded token exhausts only itself until the user's total is reached.
Every token response carries `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`
(seconds); a refused one is 429 with `Retry-After` and a `rate-limited` problem body written by the
filter. Hand-rolled with `ConcurrentHashMap` and atomics, like `UploadRateLimit`.
Alternative rejected: Bucket4j. A new dependency for a fixed window on one instance; it is the
upgrade path if Studio runs clustered.

### D4: Usage is hourly counters, not audit rows per request
`api_token_usage(token_id, hour, requests, denied, limited, errors)` is filled from an in-memory
buffer the limit filter increments after the chain (denied = 401/403, limited = 429, errors =
5xx), flushed with `last_used_at` by the existing minute job (`INSERT … ON CONFLICT DO UPDATE`).
`GET …/usage?days=1|7|30` returns totals and daily rows. The table is a data-lifecycle store,
"API token usage" (default 90 days, at least 30, the longest summary period). One audit row per REST request would
flood `audit_event`; the spec asks for a summary, which counters give exactly.

### D5: Admin inventory on its own controller
`/api/v1/admin/tokens` (`token:admin`): list every token's metadata with owner, grants, tools,
effective expiry, last use and a `stale` flag (unused, or never used since creation, for longer
than `apitokens.stale-after`, 30 days); revoke any token (audited `TOKEN_REVOKE` with the owner);
read any token's usage. Minting and rotation stay owner-only on `/api/v1/tokens`. The UI is an
"API keys" admin tab.

### D6: One gate on the MCP transport
`McpGate` is a `@Primary McpStatelessServerTransport` bean wrapping the autoconfigured
`WebMvcStatelessServerTransport` (final; the router keeps its exact-type bean, the server takes
the interface). `setMcpHandler` wraps the SDK's `McpStatelessServerHandler`, so every JSON-RPC
request, built-in or plugin, passes through Studio code. The gate runs the delegate eagerly on the
servlet thread (the transport already blocks there), keeping the security context.
- `initialize`: instructions built from the caller's visible catalogue. `McpServerInstructions`
  is deleted; one text naming every tool would reveal tools to a restricted token.
- `tools/list`: filtered by `McpToolCatalog.offered(entry)` (allow-list; MUTATE hidden in
  read-only). `studio_help` is always offered and describes only what the caller is offered.
- `tools/call`: a tool outside the allow-list answers exactly as an unknown tool does; a MUTATE
  tool in read-only mode answers with an error result saying the installation is read-only,
  whatever its `dryRun` or `confirm`. Every call writes `MCP_TOOL_CALL` (target type `mcp-tool`,
  target the tool name, cluster from the `clusterId` argument, arguments without message bodies)
  under the caller's `Actor` (owner and token, ADR-0041), succeeded or failed by the result. It
  is bound as the audit parent, so the service rows a mutation writes nest under it.
This narrows access and adds attribution; it copies no permission check, so ADR-0046's rule
against a second policy stands.
Alternatives rejected: a `BeanPostProcessor` over tool specs (cannot filter `tools/list`); a
servlet filter re-parsing JSON-RPC bodies (a second protocol parser).

### D7: The allow-list lives on the token
`api_token.mcp_tools text[]`, empty = every tool. Chosen at mint from `GET /api/v1/mcp/tools`
(the catalogue entries the caller may use), carried on a `TokenPrincipal`
(a `StudioPrincipal` subclass in `kernel.security`, not plugin API) with the token's id and
allow-list, shown in `studio://permissions`. `StudioPrincipal` itself stays unchanged, so the
plugin API does not break and `Contract.VERSION` stays. Names of tools that later
disappear are kept and simply match nothing. Per-token read-only stays "a token with only read
grants".

### D8: A `BOOLEAN` setting kind
`mcp.read-only` is a switch. `SettingDef.Kind` gains `BOOLEAN` (`true`/`false`),
`SettingsService.bool(key)`, a Switch in Operational configuration. This departs from ADR-0047's
three kinds, so it gets an ADR.

## Risks / Trade-offs

- [Existing tokens without expiry, or older than 90 days, stop working on upgrade] → Breaking
  change stated in the commit; the admin can raise the cap.
- [Limits and usage buffer live in memory; a restart loses up to a minute of counts and resets
  windows] → Accepted on one instance, as ADR-0039 accepts it for `last_used_at`.
- [Every MCP read writes an audit row] → The spec requires it; one row per call is small next to
  the broker work a call does.
- [The gate depends on the SDK's handler interface] → An integration test lists and calls tools
  through the real endpoint, so an SDK change that bypasses the gate fails the build.

## Migration Plan

One Liquibase changeset `feature/apitokens/0002`: backfill and require `expires_at`, add the
previous-secret and `mcp_tools` columns, create `api_token_usage`. No rollback of data beyond the
changeset's own `--rollback`.
