# ADR-0172: Teams own queues and addresses, and every check names its resource

- **Status**: accepted; amends [ADR-0038](0038-dynamic-permissions-and-scope-walk.md) and
  [ADR-0130](0130-permission-reach-is-declared-and-declarations-are-checked-at-runtime.md)
- **Date**: 2026-10-06
- **Deciders**: Mahdi Amirabdollahi

## Context

[ADR-0038](0038-dynamic-permissions-and-scope-walk.md) grants a role at global, environment or
cluster scope, and a check walks cluster → environment → global. Nothing narrower exists: whoever may
read a cluster sees every queue, address, connection and live event on it, and whoever may purge may
purge every queue. Shared brokers are run by many teams, each owning queues by a naming convention
(`orders.#`, `billing.*`), and each must see and operate only its own, with nothing leaking through
lists, counts, streams, audit, tokens, assistant tools or plugins.

Three ways to get there were weighed:

- **Spring Security ACL**: one row per object identity, no patterns. Queues are created by clients
  and by auto-create, outside Studio, so ACL rows would need a sync that drifts, and "every queue
  named `orders.*`, including tomorrow's" cannot be written down at all.
- **An external policy engine (Cedar, OpenFGA)**: a language or a service to run, for a rule set
  that fits one pattern matcher.
- **Studio's own grant model on Spring method security**: keep `@PreAuthorize` and the guard, and
  add patterns and teams to the resolver Studio already has.

## Decision

1. **Teams own name patterns.** A team owns queue and address name patterns, per cluster, in the
   broker's own wildcard syntax (`.` words, `*` one word, `#` any number). Two teams' patterns may
   not overlap on a cluster, decided exactly by a dynamic program over the two patterns' words, so
   every resource has at most one owner. Members are users or directory groups, each holding one
   team-assignable role. A share gives another team a role on a pattern inside the owner's.
2. **Each permission declares where it acts.** The catalogue entry says `global`, `cluster` or
   `resource` (with its resource kinds) and the permissions it requires; `globalOnly` is gone. A
   `global` permission takes effect only through a global grant, a `cluster` one through
   global, environment or cluster grants, and a `resource` one also through the owning team's role or
   a share. A role missing what its permissions require is refused. This replaces ADR-0130's
   descriptive global-only flag with one the resolver enforces.
3. **Every check names its resource.** `PermissionResolver.can(clusterId, ResourceRef, action)` and
   `ClusterAccessGuard.requireResource` / `requireAll` / `requireCreate` are the one decision point,
   for Studio, the MCP server and plugins (`@PluginApi`). A resource the caller may not read answers
   404, the same as a missing one; a readable one they may not act on answers 403 naming the
   permission. An operation touching two resources checks both before it acts. Lists, counts and
   totals are filtered before paging, rows carry `allowedActions`, stream events are filtered per
   subscriber, API tokens may be narrowed to a pattern, and every refusal is audited.
4. **Access is read per request.** A principal no longer carries grants frozen at sign-in: its
   current access is loaded per request through a cache that a bus message invalidates on every
   replica, so a grant, a membership or a share applies to the next request without signing in again.
5. **The build enforces coverage.** An architecture test fails any public method that takes a queue
   or address name and never reaches a resource check.

## Consequences

- Teams can share clusters safely, and the Teams page, the access check and the per-resource Access
  panel explain where every right comes from.
- Breaking for plugins (contract 10, then 11): permissions declare `scope`, grants are not read from
  the principal, and work that runs as its owner declares what it needs (`OwnerWork`).
- Every new endpoint that takes a queue or address must check it, or the build fails; a reviewer
  still has to check that it is the right permission on the right name.
- Filtering per row costs CPU on large clusters; a caller with a cluster-wide grant takes a fast path.
