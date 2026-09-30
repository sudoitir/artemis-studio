## 1. Design
- [x] 1.1 Brainstorm and investigate; `/opsx:update` adds design.md, sharpens specs, replaces these tasks

## 2. Tokens backend
- [x] 2.1 Changeset `feature/apitokens/0002-rotation-lifetime-usage.sql` (D1, D2, D4, D7): backfill and require `expires_at`, previous-secret columns with partial unique index, `mcp_tools text[]`, `api_token_usage`; entities, repositories, reference schema; `api_token_usage` is a data-lifecycle store
- [x] 2.2 `ApiTokensSettings` (max-lifetime 90d, rotation-overlap 24h, stale-after 30d, token-requests-per-minute 600, token-concurrency 8, user-requests-per-minute 1200) on the module descriptor
- [x] 2.3 `TokenPrincipal` in `kernel.security`; `ApiTokenService`: required expiry within the cap, live effective expiry, previous-secret lookup within the window, late old secret → 401 + `TOKEN_REJECTED`, `rotate`, `listAll`, `revokeAny`, `usage`, `flush()` with usage upsert; unit tests
- [x] 2.4 `TokenRequestLimitFilter` (D3) with `RateLimit-*`, 429 + `Retry-After` problem, usage outcome recording; unit test for the windows
- [x] 2.5 Web: `TokensController` rotate/usage/policy, `AdminTokensController` (`token:admin`), `TokenViews`; integration tests for rotation, limits, admin inventory, cap

## 3. Settings kernel
- [x] 3.1 `SettingDef.Kind.BOOLEAN`, `SettingsService.bool`, validation; Operational configuration renders a Switch

## 4. MCP
- [x] 4.1 `McpGate` transport wrapper (D6): per-caller instructions, filtered `tools/list`, allow-list and read-only on `tools/call`, `MCP_TOOL_CALL` audit as parent; delete `McpServerInstructions`
- [x] 4.2 `McpToolCatalog.offered` used by `studio_help`, `studio://tools`, instructions; allow-list in `studio://permissions`; `mcp.read-only` setting; `GET /api/v1/mcp/tools`
- [x] 4.3 Integration tests: allow-list (list, help, catalogue, call denied + audited), read-only (hidden, refused with confirm), read call audited; fixtures pass an expiry

## 5. UI
- [x] 5.1 Export `PermissionPicker`; mint form with required expiry, grouped permission picker, MCP tool picker; rotate with one-time value and overlap end; usage drawer (1/7/30 days)
- [x] 5.2 Admin "API keys" tab: inventory, stale badge, revoke with confirmation, usage; permission alert; regenerated OpenAPI types; vitest for both panels

## 6. Docs
- [x] 6.1 ADR-0136 (tokens; amends 0039), ADR-0137 (MCP gate; amends 0046, 0054), ADR-0138 (BOOLEAN setting kind); the site copies ADRs at build
- [x] 6.2 Guide pages on API keys and MCP (en, zh, fa) where they exist; module docs regenerated

## 7. Finish
- [x] 7.1 Reviewer pass on the diff (security); findings fixed
- [x] 7.2 `just verify` green; account and admin screenshots (light, dark, empty, error)
- [ ] 7.3 PR merged on green CI with Sonar gate green; change archived; roadmap ticked
