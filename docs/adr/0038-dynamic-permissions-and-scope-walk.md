# ADR-0038: Fully dynamic permission strings, resolved once per request via a cluster→environment→global scope walk

- **Status**: accepted
- **Date**: 2026-09-05
- **Deciders**: Mahdi Amirabdollahi

## Context

`003-identity.sql` already shaped `role_permission(role_id, action)` and
`user_role(user_id, role_id, scope_type, scope_id)` with a nil-UUID `GLOBAL`
scope. Phase 8 needs to decide what `action` actually is, and how a
`clusterId`-aware check resolves against `GLOBAL` / `ENVIRONMENT` / `CLUSTER`
grants without a query per check.

## Decision

**Permission strings are data, not a Java enum** — the user's explicit
choice. `role_permission.action` stores free-form `resource:verb` strings
(e.g. `cluster:read`, `message:delete`, `queue:purge`), plus the bare
wildcard `*` and a per-resource wildcard `resource:*`. `security/Permissions`
is a catalogue of `public static final String` constants for the strings the
code actually checks and for `GET /api/v1/permissions` — a discoverability
aid, not a closed set; a role may legally hold a string the catalogue
doesn't list.

Three consequences, each addressed deliberately rather than left implicit:

1. **A typo denies silently instead of failing to compile.** Mitigated by
   `EndpointProtectionTest`, which reflects over every `@RequestMapping`
   method and asserts it resolves to a `@PreAuthorize`-annotated (or
   explicitly allow-listed) service method — a missing or misspelled
   annotation is a build failure, not a production surprise.
2. **Lock-out guards are the safety net a closed enum would otherwise
   provide**: built-in roles (`ADMIN`, `OPERATOR`, `VIEWER`) are immutable;
   the last enabled global holder of `*` cannot be disabled, deleted, or
   stripped of it; nobody can revoke their own admin grant. Enforced in
   `UserService`/`RoleService` before the mutation, in the same transaction
   as the audit write — the same "server enforces, never just the UI"
   precedent as ADR-0022's bulk cap.
3. **Wildcards must compose predictably.** `Grant.grants(permission)` checks
   the bare permission, the bare `*`, and `resource:*` for the permission's
   resource prefix — in that order, all three sufficient.

**Scope resolution happens once per request, not once per check.**
`PermissionResolver` (`@Component("perm")`, the `@perm` SpEL bean in
`@PreAuthorize` expressions) is handed a `StudioPrincipal(userId, username,
Set<Grant>)` built once at authentication time — from `user_role` for a
session, or a token's grants intersected with its owner's (ADR-0039) for an
API token. A per-request `can(clusterId, permission)` call walks:

```
match if  scope_type = GLOBAL
      or (scope_type = CLUSTER     and scope_id = clusterId)
      or (scope_type = ENVIRONMENT and scope_id = the cluster's environment_id)
```

entirely in memory — `clusterId → environmentId` comes from a small cached
map the scheduler already needs, invalidated on cluster write. This mirrors
the existing pattern of resolving cheap, request-scoped state once
(`ActorResolver.resolve()` already does this for the audit actor).

**`@PreAuthorize` lives on the service layer, not the controller.** Several
controllers share a service (`DlqController` and the message controllers
both reach `MessageService`) — a controller-level check would need
duplicating per route; a service-level check covers every current and future
caller.

**Scheduler-reached service methods are never given a fake system
principal.** `ScrapeCycle`, `AlertEvaluator`, `AlertDispatcher`, the reapers,
and the RR sweep call services with no `SecurityContext`. Each service
method was audited and classified as web-facing (annotated) or
scheduler-reached (left unannotated, or the class split if it serves both).
Populating a synthetic "system" `Authentication` to satisfy `@PreAuthorize`
uniformly was rejected — it would make a bug in the filter chain (e.g. a
forgotten annotation) invisible, since the fake principal would pass
anything.

**A per-cluster method that must not leak a cluster's existence to a
principal without access to it uses `ClusterAccessGuard.requireCluster`
(throws `NotFoundException` → `404`), not `@PreAuthorize` (which would
`403`)** — deliberate deviation from the original sketch, applied wherever a
`403` would confirm a cluster exists. `@PreAuthorize` via the global
`@perm.can(permission)` overload is used only where there is no cluster
identity to hide.

## Consequences

- A permission check is enforced server-side on every mutating and
  cluster-scoped read path; the UI reflects what `/auth/me`'s grants allow,
  but is never the enforcement (non-negotiable #2's dry-run precedent,
  extended to authorization).
- `ClusterService.list()` and cross-cluster summaries (e.g.
  `/api/v1/alerts/firing`) use `@PostFilter`, not a pre-check, so a caller
  simply sees fewer clusters rather than an error.
- Reverting to an open API is a security regression, not a neutral rollback —
  called out explicitly rather than treated as free, per the change's
  Migration Plan.

## Alternatives considered

- **A closed Java enum for permissions, with a DB check constraint
  mirroring it.** Rejected per explicit user decision; would also require a
  Liquibase check constraint duplicating the enum, breaking on every new
  permission added.
- **A synthetic "system" principal for scheduler-originated calls**, so
  every service method could carry a uniform `@PreAuthorize`. Rejected: it
  would mask exactly the class of bug (a forgotten filter-chain wiring) this
  change is trying to make loud.
