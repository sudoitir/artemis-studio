# ADR-0041: Audit actor carries real identity and token attribution

- **Status**: accepted
- **Date**: 2026-09-05
- **Deciders**: Mahdi Amirabdollahi
- **Supersedes**: [ADR-0023](0023-audit-actor-before-authentication.md)

## Context

ADR-0023 accepted a best-effort audit actor — `username` either the
authenticated principal's name or the literal `"anonymous"`, `userId`
hardcoded `null` — explicitly as a stopgap until Phase 8 landed real
authentication. That ADR's own Consequences section named this moment: "Phase
8 wires real authentication into `SecurityContextHolder`... `ActorResolver`
starts returning real names and `user_id` values into the same columns. No
schema change, no `AuditService` signature change."

Phase 8 adds one thing ADR-0023 did not anticipate: API tokens (ADR-0039)
authenticate as their owning user, but a mutation performed via a token
should be distinguishable in the audit trail from the same user acting
through the browser — otherwise an incident review can't tell "Alice logged
in and did this" from "Alice's CI token did this."

## Decision

`Actor` gains a fifth field, `tokenName` (nullable) — no new `audit_event`
column, per ADR-0023's own prediction. `Actor.displayName()` folds it into
the value written to the existing `username` column:
`"<owner> [token: <name>]"` when present, the bare username otherwise. A
4-argument legacy constructor overload keeps every non-token call site
unchanged.

`ActorResolver.resolve()` now reads a real `StudioPrincipal` from
`SecurityContextHolder` (populated by session login, API token
authentication, or OIDC — see ADR-0037/0039/0040) and returns its real
`userId` and `tokenName` (the latter set only when the principal
authenticated via an API token). `Actor.ANONYMOUS` is only reachable now for
a failed login attempt itself — every other mutating call has gone through
authentication by the time it reaches an audited service, since Phase 8
requires it (ADR-0037's non-negotiable: no anonymous-mode toggle).
`Actor.system()` is unchanged for scheduler-originated rows.

New audited actions: `LOGIN`, `LOGOUT`, `PASSWORD_CHANGE`, `TOKEN_CREATE`,
`TOKEN_REVOKE`, plus the mutating actions on users, roles, environments, and
OIDC mappings introduced by this phase.

## Consequences

- The audit trail is now fully honest: `username` and `user_id` identify a
  real person or a real token's owner, not a placeholder.
- `source_ip` remains only as trustworthy as `getRemoteAddr()` reports —
  unchanged from ADR-0023, still out of scope to fix here.
- The audit screen's user filter can become a picker over real users instead
  of free text, since `user_id` is now always populated for a user-initiated
  action.

## Alternatives considered

- **A separate `token_id`/`token_name` column on `audit_event`.** Rejected:
  folding the token name into the existing `username` value needs no schema
  change and is sufficient for an incident review to distinguish the two
  cases; a dedicated column is a small, separable addition if a future
  reporting need calls for querying by token specifically.
- **Leave `Actor.ANONYMOUS` reachable for any unauthenticated mutating
  call, as a defensive fallback.** Rejected: Phase 8's non-negotiable is that
  no such call can reach an audited service unauthenticated at all: a
  fallback here would silently mask a hole in the filter chain rather than
  surfacing it as the `401`/`403` it should be.
