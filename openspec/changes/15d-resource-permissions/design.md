# Design

## Context

See proposal.md for why. Today (`artemis-studio` main):

- Permissions are `resource:verb` strings declared per module (`PermissionDef(action, label,
  globalOnly)`) and by plugins in `plugin.json`, joined into one catalogue by
  `FeatureRegistry.catalogue()`.
- Roles hold permissions (`role_permission`); `user_role` and `identity_group_mapping` grant a role at
  GLOBAL, ENVIRONMENT or CLUSTER scope. `PermissionResolver` (`@perm`) walks cluster → environment →
  global. `ClusterAccessGuard.requireCluster` checks about 100 call sites and answers 404.
- Resource lists go through `PagedListService` and `CrossNodeAggregator` and need only
  `cluster:read`. SSE (`StreamController`) checks `cluster:read` once at subscribe.
- Broker writes go through `BrokerCommands` (check, then audit row). Deferred work re-checks the
  user through `OperatorHandoff`. Plugin assistant tools are checked in `McpPluginBridge`.
- The console gates with `useCan`, which re-implements scope matching and ignores environments.

## Goals / Non-Goals

**Goals:** one decision point for every resource check, used by Studio, plugins, MCP, SSE and
tokens; resource-level isolation by team with no leak through counts, streams, audit or errors;
safe combinations by construction; an access model administrators can see and explain.

**Non-Goals:** deny rules; per-node grants; writing broker `security-settings`; an external policy
engine.

## Decisions

### D1. Own a small grant model on Spring method security, not Spring ACL or a policy engine
Spring Security ACL stores one row per object with no patterns; queues are created by clients and
auto-create outside Studio, so ACL rows would need a sync that drifts. Cedar or OpenFGA would add a
language or a service for a rule set that fits in one matcher. Studio keeps `@PreAuthorize` and its
guard, and adds a resource-aware resolver.

### D2. Artemis wildcard patterns, exact overlap detection
Patterns use the broker's own syntax (`.` words, `*` one word, `#` zero or more words), so
operators reuse what they already write in `security-settings`. Two patterns overlap iff their token
automata intersect; with only `*` and `#` this is decidable by a small dynamic program over the two
token lists (`#` may consume 0..n tokens, `*` exactly one, literals must be equal or meet a
wildcard). Property tests check it against brute-force enumeration over a small alphabet.

### D3. Teams as first-class data
Tables in the kernel security schema: `team`, `team_pattern(team, cluster, kind, pattern)`,
`team_member(team, principal_type, principal_id, role)`, `team_share(owner_team, target_team,
cluster, kind, pattern, role)`. `role.team_assignable`. No overlap between teams on a cluster keeps
ownership single-valued, which makes "owner" displayable and the index a simple lookup.

### D4. Permission scope in the catalogue
`PermissionDef` becomes `(action, description, scope GLOBAL|CLUSTER|RESOURCE, resourceKinds,
requires)`; `globalOnly` is removed everywhere (code, manifest schema, SDK). The resolver uses scope:
a GLOBAL permission passes only through a global grant; CLUSTER through global/env/cluster grants;
RESOURCE through those or a team role/share on the resource. A resource-less check of a RESOURCE
permission asks "may the caller do this on *some* resource of the cluster" and is used only for page
gating, never to authorise a change.

### D5. One resolver, compiled per cluster
`PermissionResolver.can(clusterId, ResourceRef, action)` evaluates: role grants (existing walk) ∪
owning team's member role ∪ shares covering the name. A `TeamIndex` holds, per cluster, compiled
patterns → team, rebuilt from the database on team change events and on replica fan-out. Principal
team roles are resolved per request from the database through a short cache keyed by an access
version that every grant/role/team change bumps, so changes apply on the next request (fixes the
re-login bug) and on every replica.

### D6. Filter before paging; send allowed actions with each row
List services filter rows by the resolver before sorting, paging and counting, so totals never
reveal hidden names. Each row carries `allowedActions` computed by the same resolver; the console
gates from those and from a per-cluster capability summary (`/api/v1/me/access?clusterId=`) instead
of re-implementing matching. `useCan` keeps its name and gains a resource argument.

### D7. Composite operations declare all their resources
`BrokerCommands`, message services, DLQ, transfers, diverts and bulk operations list the
(resource, permission) pairs they touch; a helper checks all before any broker call. Bulk plans
store them and `OperatorHandoff` re-checks at run.

### D8. Streams decide per event
`TopicDef` gains `permission` and a resource extractor. The stream fan-out evaluates each event per
subscriber with the cached access; events listing several resources are trimmed. Subscriptions are
not dropped on access change: the access version makes the next decision current.

### D9. Denials audited once per minute per key
A listener records `AuthorizationDeniedEvent` and guard refusals as audit events with outcome
`REFUSED`, deduplicated per (actor, permission, cluster, resource) per minute with a count.
`BrokerCommands` opens its audit row before the check.

### D10. Plugin API
Published: `ResourceRef`, `PermissionResolver.can(clusterId, ResourceRef, action)`,
`ResourceFilter`, and `OwnerWork` (declare needs at publish; Studio checks at publish and before each
run, suspends on loss). MCP tool `scope: resource` with `resourceArg` and `resourceKind`. SDK
`useCan(perm, {clusterId, kind, name})`. `Contract.VERSION` bumps (breaking).

### D11. Classification of the core permissions
`GLOBAL` is every permission whose guards check it without a cluster: `user:admin`, `team:admin`,
`token:admin`, `diagnostics:bundle`, `settings:read`, `data:read`, `data:write`, `environment:read`,
`environment:write`, `governance:read`, `governance:write`. `CLUSTER` is everything a guard checks
against a cluster that is not a queue or an address: `cluster:read`, `cluster:write`,
`config:write`, `config:apply`, `alert:read`, `alert:write`, `connection:read`, `connection:close`,
`rr:write` and `settings:write` (credential rotation checks it against a cluster). `RESOURCE` acts on one
queue or address: `QUEUE` for `queue:read`, `queue:create`, `queue:update`, `queue:delete`,
`queue:pause`, `queue:purge`, `message:read`, `message:move`, `message:delete`, `message:clear`
and `capture:write`; `ADDRESS` for `address:read`, `address:create`, `message:send` and `divert:write`.
Non-obvious cases:

- `capture:write` is `QUEUE`: a capture subscription is defined by a queue-name pattern, and the
  addresses it taps are derived from it, so the owner of the queues owns the capture.
- `message:clear` is `QUEUE` and `queue:delete` stays `QUEUE` although it also destroys addresses
  (`DELETE_ADDRESS`); an `address:delete` is decided with the enforcement work (2.2).
- `cluster:write` registers or removes a cluster (a global check) and also gates cluster-level writes;
  it is `CLUSTER`, so a global grant makes the global checks pass.
- `requires` follows the reads a screen needs: the queue-acting permissions require `queue:read`;
  `message:send`, `divert:write`, `address:create` require `address:read`; `connection:close`
  requires `connection:read`; `alert:write` requires `alert:read`; `config:apply` requires
  `config:write`; `cluster:write` requires `cluster:read`. The send target of a move is checked
  separately, so `message:move` does not require `message:send`.
- `queue:read` and `address:read` are named in the security kernel's `Permissions` because modules other
  than the queues' own require them.

## Risks / Trade-offs

- [Filtering large lists per row costs CPU] → compiled index lookup is a few token comparisons; a
  caller with a cluster-wide grant skips per-row matching entirely.
- [A missed call site leaks a resource] → build-time test fails any controller or service method
  taking a queue or address name without a resource check; integration tests per feature as a
  team-only user.
- [Overlap rule blocks a legitimate layout] → shares cover cross-team access without overlap.
- [Stream fan-out per subscriber cost] → decision cached by (subscriber access version, resource).

## Migration Plan

None kept: no backward compatibility before the stable release. Built-in roles are re-seeded,
`globalOnly` is removed, plugins must declare `scope`. Breaking commits are marked.
