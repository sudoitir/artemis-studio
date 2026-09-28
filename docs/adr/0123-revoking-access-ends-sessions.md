# ADR-0123: Revoking access ends the user's sessions, and sessions really live in JDBC

- **Status**: accepted
- **Date**: 2026-09-28
- **Deciders**: Mahdi Amirabdollahi

## Context

[ADR-0037](0037-session-cookie-authentication.md) chose session-cookie authentication, with sessions
in `spring-session-jdbc` so every instance on one database shares them. A session holds the
`StudioPrincipal`, whose grants are resolved at sign-in ([ADR-0038](0038-dynamic-permissions-and-scope-walk.md)).
[ADR-0103](0103-plugin-installer-tier-and-step-up-reauthentication.md) accepted that consequence: a
change to a user's grants reaches their browser only at the next sign-in, up to the 8 h session
timeout later. For widening access that is harmless. For taking it away it is not: a disabled user,
or one whose grant was removed, keeps working with the old rights for hours. API tokens do not have
this problem, because they intersect with the owner's live grants on every request (ADR-0039).

A security review also found that Studio's sessions were never in JDBC. Spring Boot 4 split
auto-configuration into per-technology modules. Studio depended on `spring-session-jdbc` itself,
which lacks `spring-boot-session-jdbc`, so no `SessionRepository` was ever configured and sessions
lived in the servlet container's memory. They were lost on restart, not shared between instances,
and could not be found by user.

## Decision

- Studio depends on `spring-boot-starter-session-jdbc`, so sessions are stored in the
  Liquibase-owned `spring_session` tables, as ADR-0037 intended. Everything stored in a session is
  `Serializable` (`Grant`, the pending OIDC step-up).
- We will end every session of each affected user when access is taken away. That means a user
  is disabled, a grant is removed from a user, or a role's permissions change (all members). Sessions
  are found through Spring Session's principal-name index, which is the username. They are deleted
  after the revoking transaction commits, so a rolled-back change signs nobody out.
- Adding a grant leaves sessions alone. The user sees the wider access at their next sign-in, as
  before.

This supersedes the "grants are fixed until the next sign-in" consequence of ADR-0103 for
revocation. ADR-0103's decision is otherwise unchanged.

## Consequences

- A revocation takes effect on the affected user's next request, which answers 401, and the UI
  returns to sign-in.
- Editing a role signs out everyone who holds it. That is the price of immediate effect, and it
  only happens on an administrative action.
- Sessions now survive a restart and are shared between instances. The session cookie is Spring
  Session's `SESSION`, not the container's `JSESSIONID`, so users are signed out once on upgrade.
- A new session attribute must be `Serializable`. A non-serializable one fails at the end of the
  request that sets it, which tests catch.

## Alternatives considered

- **Re-resolve grants on every request**, as API tokens do. That costs a grant query on every
  browser request and changes the principal's lifecycle. Ending sessions is cheaper and has the
  same effect for the one case that matters, revocation.
- **Keep in-memory sessions and document single-instance use.** This contradicts ADR-0037 and the
  HA posture in `application.yml`, and it still cannot find a user's sessions to end them.
