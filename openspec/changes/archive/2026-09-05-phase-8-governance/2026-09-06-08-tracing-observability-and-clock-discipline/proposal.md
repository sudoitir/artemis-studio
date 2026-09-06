## Why

`docs/roadmap.md` Phase 8 is "Local users → full RBAC · per-environment grouping ·
read-only enforcement · OIDC/SSO," and `openspec/project.md` already records it as
next. The application is completely open today: `SecurityConfig` is an explicit
placeholder (`csrf.disable()` + `anyRequest().permitAll()`), gated only by a
startup WARN when bound to a non-loopback address. That leaves stored broker
credentials, every destructive message operation, the runtime bulk safety cap
itself, and notification-channel secrets reachable by anyone who can reach the
port. The audit trail — a stated non-negotiable — consequently records
`username = "anonymous"` on every row, a stopgap ADR-0023 accepted explicitly
pending this phase. `003-identity.sql` already shipped the `app_user` / `role` /
`role_permission` / `user_role` schema for this in Phase 1, unused until now.

## What Changes

- Add session-cookie authentication (Spring Security, `spring-session-jdbc`),
  local users with bcrypt password hashes, a first-run admin bootstrap, forced
  password change, and per-(username, IP) login throttling.
- Add authorization as **fully dynamic permission strings** (`resource:verb`,
  wildcards) held in `role_permission`, resolved per request by walking
  cluster → environment → global scope (`user_role.scope_type`), enforced with
  `@PreAuthorize` at the service layer. Built-in `ADMIN`/`OPERATOR`/`VIEWER`
  roles are seeded and immutable; custom roles are supported. Last-global-admin
  guards prevent lockout.
- Add `environment` as a first-class grouping: CRUD API, cluster membership
  surfaced in views, and environment-scoped grants finally usable.
- Add revocable API tokens owned by a user, narrowable to a subset of that
  user's own grants, authenticated via a bearer filter ahead of the session
  filter.
- Add OIDC/SSO login (`spring-boot-starter-oauth2-client`) with JIT user
  provisioning and a configurable claim → role mapping, re-applied on every
  login. Local login remains available.
- Rework the audit actor: `ActorResolver` now resolves a real principal and
  user id instead of the literal `"anonymous"`; new audited actions for every
  identity/session/token/role/environment mutation.
- Frontend: login screen, forced-password-change screen, user menu, an
  `/admin` area (users, roles, grants, tokens, environments, OIDC mapping), a
  `<Can>` permission gate, router auth guard, and 401 handling in the shared
  API client.
- **BREAKING**: every existing API endpoint (except `/api/v1/auth/**` and
  `/actuator/health`) now requires authentication; unauthenticated requests
  that previously succeeded now return `401`. The dev compose stack and any
  scripted callers must be updated to log in (or use an API token) first.

## Capabilities

### New Capabilities

- `identity-and-sessions`: local users, password auth, session cookies, login
  throttling, first-run bootstrap, forced password change, `GET /me`.
- `authorization`: dynamic permission model, scope resolution (global /
  environment / cluster), built-in and custom roles, permission catalogue,
  last-admin protection.
- `environments`: environment CRUD and cluster grouping.
- `api-tokens`: personal API tokens narrowable to a subset of the owner's
  grants, bearer authentication.
- `oidc-sso`: OIDC authorization-code login, JIT provisioning, claim → role
  mapping.

### Modified Capabilities

- `audit-log`: actor resolution now carries a real `user_id` and username;
  new audited action types for identity/session/role/token/environment
  events; the audit screen's user filter becomes a picker over real users.
- `cluster-registration`: cluster listing and detail are filtered by the
  caller's `cluster:read` grant; cluster views gain environment id/name/colour.
- `realtime-stream`: `/api/v1/stream` requires authentication and the
  requested `clusterId` is checked against the caller's grants.
- `message-operations`: every mutating message endpoint requires the matching
  permission (`message:send`, `message:delete`, `queue:purge`, etc.) at the
  target cluster's scope.
- `dlq-management`: DLQ replay-all requires `message:move`/`queue:purge` at
  scope, same as the primary message path it shares.
- `alerting`: alert rule and notification channel writes require `alert:write`
  at scope; reads are filtered by `cluster:read`.
- `studio-settings`: settings writes require `settings:write` (global) —
  closes the hole where an unauthenticated caller could raise the bulk
  safety cap.
- `request-reply-tracing`: reading expectations/flows requires `cluster:read`;
  creating, updating, or deleting an expectation requires `cluster:write`, at
  the target cluster's scope.

## Impact

Backend: new `security/`, `persist/` (user, role, token, environment, OIDC
mapping) and `web/` classes; `SecurityConfig` rewritten; every mutating
service method gains `@PreAuthorize`; `pom.xml` gains
`spring-boot-starter-oauth2-client` and `spring-session-jdbc`; Liquibase
changeset `014-identity.sql`. Frontend: new `auth/` and `admin/` feature
folders, router and API-client changes touching every screen indirectly (via
the auth guard and `<Can>` gating), regenerated `schema.d.ts` and
`web/openapi.json`. Five new ADRs (0037–0041), superseding ADR-0023. Full
design already worked through with the user; see
`/home/sudoit/.claude/plans/plan-for-next-phase-happy-acorn.md` for the
detailed rationale this proposal and design.md are drawn from.
