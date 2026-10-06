## Why

Studio grants rights only to a whole installation, environment or cluster. Anyone who can read a
cluster sees every queue, address, connection and live event on it, and anyone who may purge may
purge every queue. Shared brokers are run by many teams, and each team must see and operate only its
own queues and addresses, by naming convention, with nothing leaking through lists, counts, streams,
audit, tokens, assistant tools or plugins. The roadmap's audit, approvals, saved views and
compliance changes all need this resource-level check to build on, so it lands before them.

## What Changes

- **Teams.** A team owns queue and address name patterns (Artemis wildcard syntax) on
  chosen clusters. Patterns of different teams may not overlap on a cluster. Members (users and
  directory groups) hold a team role. A team can share one of its patterns with another team at a
  chosen team role. Team admins manage their own members.
- **Resource-scoped checks.** **BREAKING** Every operation on a queue, address, message,
  consumer, producer, divert, connection or session is checked against the resource it touches.
  Lists, counts and totals contain only what the caller may see. Operations that touch two
  resources need the right on both. Creating a queue or address is allowed only inside the
  caller's patterns.
- **Permission catalogue.** **BREAKING** Each permission declares the scope it acts at
  (global, cluster or resource), the resource kinds it applies to, and the permissions it needs.
  `globalOnly` is removed. New permissions: `queue:read`, `address:read`, `address:create`,
  `rr:write`, `connection:read`, `team:admin`. Built-in roles are rebuilt to cover every permission,
  and Team Viewer, Team Operator and Team Admin are added.
- **Live streams, audit, tokens.** Stream events are filtered per subscriber and per
  resource. Every refused request is audited. The audit trail is filtered by resource. API token
  grants may name a resource pattern. Access changes apply on the next request without re-login.
- **Plugin API.** **BREAKING** Plugin permissions declare `scope` and `resourceKinds`;
  plugins check resources through the published resolver; assistant tools may be resource-scoped;
  work that runs as its owner declares what it needs and is re-checked on every run.
  `Contract.VERSION` is bumped.
- **Console.** A Teams page, team-aware role editor, scope picker for grants, an access check
  that explains where each right comes from, owner shown on queues and addresses, actions explained
  where they are refused, team-aware empty states.
- **Fixes.** Queue configuration readable by any signed-in user; grants only assignable at
  global scope in the UI; environment grants ignored by the UI; a new grant needing re-login;
  wildcard token grants intersecting to nothing; token grants surviving a deleted scope; stream
  topics not gated by their feature permission; a disabled feature's permission still granting;
  request-reply tracing writes gated by `cluster:write`; addresses created under `queue:create`.

## Capabilities

### New Capabilities
- `team-access`: teams, owned patterns, membership, shares, and resource-level checks.

### Modified Capabilities
- `authorization`: catalogue scopes and dependencies, built-in roles, grant scopes in the
  UI, resource-scoped checks and filtering, the access check.
- `realtime-stream`: per-event, per-resource filtering and live re-evaluation.
- `api-tokens`: resource-pattern grants and the intersection fixes.
- `audit-log`: refused requests are audited; the trail is filtered by resource.
- `plugin-runtime`: plugin permission scopes, resource-scoped tools, owner-run work.

## Out of scope

- Deny rules (grants only add).
- Per-node or per-broker grants.
- Enforcing teams inside the broker's own `security-settings`.

## Depends on

- 01-permission-catalog, 05-api-tokens-and-mcp-scopes (both done).
- ADR-0038 (dynamic permissions), ADR-0039 (token grants), ADR-0071 (broker commands), ADR-0093 (bulk hand-off), ADR-0114 (plugin tools), ADR-0123 (revocation), ADR-0130 (catalogue).

## Execution

**Subagent-driven**: the model and resolver first, then enforcement, streams and audit, the plugin API and the console as separate PRs.

## Impact

Kernel security, every broker-facing feature module, realtime, audit, API tokens, MCP, the plugin API and SDK, the web console, the Liquibase schema. Breaking for plugin manifests (`globalOnly` removed) and for any client relying on cluster-wide lists for team users.
