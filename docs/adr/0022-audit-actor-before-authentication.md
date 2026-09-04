# ADR-0022: Audit actor resolution before authentication exists

- **Status**: accepted
- **Date**: 2026-09-04
- **Deciders**: Mahdi Amirabdollahi

## Context

Non-negotiable #3: "Every mutating call writes an `audit_event` in the same
transaction as the command, before the broker call, updated with the outcome."
ADR-0011 records the mechanism: `AuditService` persists a `PENDING` row before
the broker call and updates the same managed entity to `SUCCESS` / `FAILURE`
after, in one transaction.

Phase 3 is the first phase where an operator does something worth attributing —
moving, deleting, purging messages. But authentication is Phase 8. Today
`config/SecurityConfig` is `permitAll()` and `AuditService` writes a hard-coded
`username = "system"` for the register / rotate-credentials flows.

The `audit_event` table (changeset `004-audit.sql`) already has the columns for a
real actor: `username`, `source_ip inet`, `request_id`, `user_id` (FK to
`app_user`, nullable). Three of them — `source_ip`, `request_id`, `user_id` — are
not mapped on `AuditEventEntity`; `ddl-auto=validate` tolerates the extra DB
columns.

The question is what to record as the actor now, so that the Phase 3 audit trail
is honest and Phase 8 fills the same columns with real users and no migration.

## Decision

We will resolve and record a best-effort actor now, from the request, and fill
the columns that already exist.

- **`security/ActorResolver`** (a `@Component`) returns
  `Actor(username, sourceIp, requestId, userId)`:
  - `username` — the authenticated principal's name from
    `SecurityContextHolder` when one is present; otherwise the literal
    `"anonymous"`. Not null, ever.
  - `sourceIp` — `HttpServletRequest.getRemoteAddr()`.
  - `requestId` — the `X-Request-Id` request header when present, else a fresh
    `UUID`. Lets an operator or a proxy correlate an audit row to a request.
  - `userId` — null until Phase 8 (no `app_user` rows exist yet).
- **`ActorResolver.system()`** returns the fixed system actor for
  scheduler-originated writes. The scheduler does not serve an HTTP request.
- **`AuditService.begin(...)` takes an `Actor`** and stores all four values.
  Existing callers (`ClusterService.register` / `rotateCredentials`) pass
  `actorResolver.resolve()`; the `SYSTEM_USER` constant becomes
  `ActorResolver.system()`.
- **`AuditEventEntity` gains `@Column` mappings** for `request_id`, `source_ip`
  (`inet` ↔ `String`), and `user_id`. No changeset — the columns are already
  released.
- **No authorization.** The resolver records who is acting; it does not decide
  whether they may. Permission checks are Phase 8. A mutating endpoint in Phase 3
  is reachable by anyone who can reach the API, exactly as every other endpoint
  is today (`SecurityConfig` warns at startup if not bound to loopback).

## Consequences

- The Phase 3 audit trail is honest: it says `anonymous` when it does not know
  who acted, records the source IP and a correlatable request id, and never
  pretends a human was `system`.
- Phase 8 wires real authentication into `SecurityContextHolder` and populates
  `app_user`; `ActorResolver` starts returning real names and `user_id` values
  into the same columns. No schema change, no `AuditService` signature change.
- `source_ip` is only as trustworthy as `getRemoteAddr()` — behind a reverse
  proxy it is the proxy's address unless the proxy is configured to forward and
  Studio to trust `X-Forwarded-For`. That configuration is out of scope here;
  the column records what the servlet container reports.
- One more component in the request path, but a trivial one.

## Alternatives considered

- **Leave `username` null until Phase 8.** Rejected — every Phase 3 audit row
  would be indistinguishable between "no auth configured" and "we lost track of
  who acted". `anonymous` plus a source IP and request id is a real record.
- **Pull Phase 8's local users + HTTP Basic forward into Phase 3.** Rejected —
  it drags an entire phase (user store, password hashing, login, session) into a
  message-operations phase for one column's sake. ADR-0011 and the roadmap keep
  auth in Phase 8.
- **Record only a request id, no username field.** Rejected — the `username`
  column exists and the audit-log screen filters on it; a literal `anonymous` is
  a usable filter value and a truthful one.
