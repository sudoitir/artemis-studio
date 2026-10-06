# Tasks

## 1. Model, catalogue and resolver
- [x] 1.1 Wildcard pattern matcher and exact overlap test for Artemis syntax; property tests against brute-force enumeration pass
- [x] 1.2 Catalogue: `PermissionDef` gains scope, resourceKinds, requires; `globalOnly` removed; every core permission classified; new permissions `queue:read`, `address:read`, `address:create`, `rr:write`, `connection:read`, `team:admin`; consistency check reports unknown requires and cycles (`PermissionCatalogueTest` passes)
- [x] 1.3 Liquibase: team, team_pattern, team_member, team_share, role.team_assignable; re-seed built-in roles (Administrator, Operator, Viewer, Team Viewer, Team Operator, Team Admin); built-in coverage test fails on an unclassified new permission
- [x] 1.4 Team service and REST API (CRUD, patterns with overlap refusal, members, shares, pattern preview, unowned resources) with `user:admin` / `team:admin` rules; audited; service tests pass
- [x] 1.5 Resolver: `can(clusterId, ResourceRef, action)`, scope semantics, disabled-feature permissions grant nothing, TeamIndex, access version cache so changes apply next request; combination matrix tests (union of roles, team + share, platform + team, cross-cluster) pass
- [x] 1.6 Role save refuses missing requires and non-resource permissions in team-assignable roles; tests pass

## 2. Enforcement
- [x] 2.1 `ClusterAccessGuard.requireResource` (404 when unreadable, 403 naming permission when readable); cluster listing includes team clusters; tests pass
- [x] 2.2 Queue, address, message, DLQ, divert, capture, request-reply, transfer and bulk endpoints check their resources, including composites (move, retry, divert, bulk plan and run); queue configuration endpoint checked; integration tests as a team-only user pass
- [x] 2.3 Lists and counts filtered before paging (`PagedListService`, `CrossNodeAggregator`); rows carry `allowedActions`; connections and sessions trimmed; summary totals filtered; tests pass
- [x] 2.4 Create inside patterns only (`queue:create`, `address:create`); request-reply writes use `rr:write`; tests pass
- [x] 2.5 Alerts, metric history, search, governance content policy, SQL and audit read honour resource access; tests pass
- [x] 2.6 Architecture test fails any method taking a queue or address name without a resource check; passes on the codebase

## 3. Streams, tokens and audit
- [x] 3.1 Topics declare permission and resource extractor; per-subscriber per-event filtering and trimming; access changes apply to open streams; tests pass
- [ ] 3.2 Token grants may carry kind + pattern; wildcard intersection fixed; token grants removed with their scope; tests pass
- [ ] 3.3 Refusals audited with dedup count; `BrokerCommands` audits before the check; tests pass
- [x] 3.4 `GET /api/v1/me/access` capability summary and access check endpoint (user × cluster × resource with sources); tests pass

## 4. Plugin API
- [ ] 4.1 Manifest schema: `scope`, `resourceKinds`, `requires`; `globalOnly` refused; validator tests pass
- [ ] 4.2 Published `ResourceRef`, resource `can`, `ResourceFilter`, `OwnerWork` (check at publish and before each run, suspend on loss) under `@PluginApi`; plugin messaging `AccessCheck` uses resource checks; tests pass
- [ ] 4.3 MCP plugin tools `scope: resource` with resource argument; activation checks; Studio's own MCP tools use resource checks; tests pass
- [ ] 4.4 SDK `useCan(perm, resource)` and `allowedActions` types; template plugin updated; `Contract.VERSION` bumped; japicmp/SDK checks pass

## 5. Console
- [x] 5.1 `useCan` reads server access summary and row `allowedActions` (environment-scope bug gone); component tests pass
- [x] 5.2 Teams page: list, Patterns with live preview and overlap error, Members (Team Admin editable), Shares, Unowned; component tests pass
- [x] 5.3 Role editor: team-assignable toggle, scope badges, requires auto-add with note; grant dialogs offer global/environment/cluster scope; component tests pass
- [ ] 5.4 Access check drawer with sources; queue/address Access panel; owner chip in lists; component tests pass
- [ ] 5.5 Gating: hide vs disabled-with-reason, send-target pickers filtered, create shows allowed patterns and validates live, team-aware empty states, revoked resource turns not-found; component tests pass
- [ ] 5.6 Visual QA sweep (team user vs admin, light and dark, empty and error states) on an isolated stack; findings fixed; stack removed

## 6. Docs and release
- [ ] 6.1 ADR for resource-scoped authorization and teams; user docs for teams and permissions; `just verify` passes
- [ ] 6.2 PRs merged on green CI and a clean Sonar gate; Studio release cut and published
