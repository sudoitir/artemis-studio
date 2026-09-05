# ADR-0037: Session-cookie authentication, not bearer tokens, for the browser

- **Status**: accepted
- **Date**: 2026-09-05
- **Deciders**: Mahdi Amirabdollahi

## Context

Phase 8 (governance) requires the app to authenticate every user. The SPA
opens its realtime channel with a bare `EventSource`
(`web/src/api/stream.ts:57`): `EventSource` cannot set an `Authorization`
header. Non-negotiable #5 (realtime is SSE only) and the product's reliance
on that stream for topology, health, and queue signals make this a hard
constraint, not a preference.

`spring-boot-starter-security` was already on the classpath
(`pom.xml:90`); `config/SecurityConfig` was a one-bean placeholder
(`csrf.disable()` + `anyRequest().permitAll()`) with a startup
`warnIfExposed` check (see ADR-0023, superseded by ADR-0041).
`003-identity.sql` had already created `app_user`, `role`, `role_permission`,
`user_role` — released, unused until now.

## Decision

We will authenticate the browser with a server-side session, carried in an
`HttpOnly`, `SameSite=Lax` cookie:

- `POST /api/v1/auth/login` (JSON body — not the default form-encoded login,
  since the SPA sends JSON), `POST /api/v1/auth/logout`, `GET /api/v1/auth/me`.
- Session storage is `spring-session-jdbc`, schema owned by our own Liquibase
  changeset (`014-identity.sql`) with
  `spring.session.jdbc.initialize-schema=never` — consistent with "Liquibase
  owns the schema" (non-negotiable #7), not the library's auto-DDL. This also
  survives an app restart, a prerequisite for v1.0's multi-instance HA without
  a second migration.
- CSRF protection is re-enabled:
  `CookieCsrfTokenRepository.withHttpOnlyFalse()` +
  `CsrfTokenRequestAttributeHandler` (the plain handler, not the
  BREACH-XOR-encoding one — the SPA reads the raw cookie value directly). A
  dedicated `CsrfCookieFilter` forces the token to resolve on every request,
  so the `XSRF-TOKEN` cookie is set on a browser's very first request, not
  only after something happens to read it — see the Consequences section
  for the bug this fixed.
- `AuthService.login`/`logout`/`changePassword` build a `StudioPrincipal`
  directly and persist it via `SecurityContextRepository`
  (`DelegatingSecurityContextRepository` of
  `HttpSessionSecurityContextRepository` +
  `RequestAttributeSecurityContextRepository`) — there is no
  `UsernamePasswordAuthenticationFilter` in the chain, since the login body is
  JSON, not form-encoded.
- Password hashing is
  `PasswordEncoderFactories.createDelegatingPasswordEncoder()` (bcrypt by
  default, `{bcrypt}`-prefixed, upgradeable later with no migration).
- First-run bootstrap: on `ApplicationReadyEvent`, if `app_user` is empty,
  `AdminBootstrap` creates `admin` with a random password, logs it once at
  WARN, and sets `must_change_password = true`. Every endpoint except
  `/auth/{password,logout,me}` returns `423 Locked` until the password is
  changed. The old `warnIfExposed` check is deleted — its reason for
  existing (an always-open API) is gone.
- Login throttling: `LoginAttemptLimiter`, an in-memory per-(username,
  source-IP) exponential lockout modeled on `scheduler/NodeCallLimiter`.
  Single-instance only — a `ponytail:` comment marks the ceiling, revisited
  at v1.0's multi-instance HA item.

API tokens (ADR-0039) are the mechanism for scripted, non-browser callers;
they are exempt from CSRF, since a bearer credential carries no ambient
browser context for a cross-site request to ride on.

## Consequences

- Every `/api/**` path other than `/auth/login` and `/auth/providers`
  requires authentication, with no toggle to disable it — the app is no
  longer reachable by anyone who can hit the port.
- The SPA is coupled to same-origin deployment for the cookie to work — this
  was already true (`SpaRoutingConfig` serves the SPA from the API's own
  origin), so it is not a new constraint.
- **Two real bugs found in manual verification, both fixed before merge**:
  (1) the CSRF cookie was never set on a browser's first-ever request, so its
  first login attempt was always rejected by `CsrfFilter` before
  authentication even ran — fixed by `CsrfCookieFilter` forcing eager token
  resolution; (2) a successful password change didn't refresh the session's
  cached `StudioPrincipal`, so the account stayed locked (`423`) until a
  fresh login — fixed by re-authenticating with a freshly built principal
  inside `AuthService.changePassword`.
- Every pre-existing test that called the API unauthenticated needed
  updating (`AdminAuthenticationExtension`, applied to twelve existing test
  classes) — intentional, not incidental breakage.

## Alternatives considered

- **JWT in memory, sent as an `Authorization` header.** Rejected: forces the
  SSE stream's auth into the query string (leaks into access logs) or a
  fetch-based `EventSource` polyfill — real complexity for no benefit in a
  same-origin SPA.
- **Spring Security's default form login.** Rejected: the SPA's login
  request is a JSON POST, not `application/x-www-form-urlencoded`; a custom
  `AuthController` handling the JSON body directly is simpler than
  reconfiguring the default filter's content-type handling.
