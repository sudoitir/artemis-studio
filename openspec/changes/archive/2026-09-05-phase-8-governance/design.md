## Context

See `proposal.md` - Why for motivation. Current state that shapes the approach:

- `spring-boot-starter-security` is already on the classpath (`pom.xml:90`);
  `config/SecurityConfig.java` is a one-bean placeholder
  (`csrf.disable()` + `anyRequest().permitAll()`) with a startup
  `warnIfExposed` check.
- `003-identity.sql` already created `app_user`, `role`, `role_permission`
  (`role_id, action`), and `user_role` (`user_id, role_id, scope_type,
  scope_id`, nil-UUID default for `GLOBAL`) — released, unused, not to be
  edited.
- `002-estate.sql` already created `environment(id, name, colour,
  sort_order)` and `cluster.environment_id`; no Java uses it.
- `security/Actor.java` / `security/ActorResolver.java` already read
  `SecurityContextHolder` and fall back to `"anonymous"`; `userId` is
  hardcoded `null`.
- The SPA opens `GET /api/v1/stream` with a bare `EventSource`
  (`web/src/api/stream.ts:57`), which cannot set an `Authorization` header —
  this is the fact that decides the authentication mechanism (see Decision 1).
- `web/src/api/client.ts` is the single `fetch` wrapper every hook goes
  through — the one place to add CSRF headers and 401 handling.
- Full option analysis for every decision below was already worked through
  with the user in `/home/sudoit/.claude/plans/plan-for-next-phase-happy-acorn.md`;
  this document records the resulting decisions and their rationale for the
  implementation record, not a re-derivation.

## Goals / Non-Goals

**Goals:**
- Every API endpoint other than login/health/static assets requires
  authentication, with no toggle to turn it off.
- A permission check is enforced server-side on every mutating and
  cluster-scoped read path; the UI reflects it but is never the enforcement.
- The existing `003-identity.sql` schema is used as designed, extended (never
  edited) via a new changeset.
- Local login, API tokens, and OIDC all resolve to the same principal shape
  so one authorization code path serves all three.

**Non-Goals:**
- Multi-instance HA coordination (v1.0) — the login throttle and token
  last-used buffering are single-instance, in-memory, and explicitly marked
  as such.
- RP-initiated OIDC logout, DB-configured OIDC providers, password-reset
  email, per-token IP allow-listing, a global read-only kill switch. Each
  is a small, separable addition if asked for later.
- Redesigning the permission catalogue as a closed enum — the user chose
  fully dynamic strings; enum-like safety is achieved instead through the
  lock-out guards (Decision 4) and the endpoint-protection test (tasks.md).

## Decisions

### 1. Session cookie, not a bearer token, for the browser

`EventSource` cannot set custom headers, and the realtime stream is core to
the product. A cookie is sent automatically on both `fetch` and
`EventSource` requests to the same origin. Alternative considered: JWT in
memory, refreshed and passed as a header — rejected because it forces the
stream's auth into the query string (leaks into access logs) or a
fetch-based EventSource polyfill, adding complexity for no benefit in a
same-origin SPA. API tokens (Decision 5) cover the case a bearer token
actually serves — scripted, non-browser callers.

Session storage is `spring-session-jdbc` with
`spring.session.jdbc.initialize-schema=never` and its tables owned by our own
Liquibase changeset, consistent with "Liquibase owns the schema." Verify the
exact Spring Session 4.x / Boot 4.1 property names via `ctx7` before adding
the dependency, per project rule.

CSRF is re-enabled (it was `disable()`d in the placeholder):
`CookieCsrfTokenRepository.withHttpOnlyFalse()` with
`CsrfTokenRequestAttributeHandler` (opts out of BREACH-protection XOR
encoding, because the SPA reads the raw cookie value directly). Login and
logout responses re-issue a fresh CSRF cookie, since Spring Security clears
it on both. Token-authenticated requests (Decision 5) are exempt from CSRF —
they carry no ambient browser credential, so there is nothing for a
cross-site request to ride on.

### 2. Fully dynamic permission strings, catalogued but not closed

The user's explicit choice over a Java enum. `role_permission.action` stores
arbitrary `resource:verb` strings (plus `resource:*` and the bare `*`
wildcard). `security/Permissions.java` holds `public static final String`
constants for every string the code actually checks — a catalogue for
discoverability and for `GET /api/v1/permissions`, not a closed set; a role
may legally hold a string the catalogue doesn't list.

Trade-off accepted: a typo'd permission string in a `@PreAuthorize`
expression fails silently (denies everyone, including the check's author,
rather than failing to compile). Mitigated by `EndpointProtectionTest`
(tasks.md, section 3) asserting every non-GET handler resolves to a
`@PreAuthorize`-annotated service method — a missing or misspelled
annotation shows up as a test failure, not a production surprise.

Alternative considered: closed enum matching `role_permission.action` via a
check constraint. Rejected per explicit user decision; also would have
required a Liquibase check constraint duplicating the enum, breaking on
every new permission.

### 3. Scope resolution happens once per request, not once per check

`security/PermissionResolver` builds a `StudioPrincipal(userId, username,
Set<Grant>)` once, at authentication time, from `user_role` (or the token's
narrowed grants). A per-request `can(clusterId, permission)` call walks:
global → environment (via a small in-memory `clusterId → environmentId` map,
already needed by the scheduler and invalidated on cluster write) → cluster
— entirely in memory, no query per check. This mirrors the existing pattern
of resolving cheap, request-scoped state once (`ActorResolver.resolve()`
already does this for the audit actor).

### 4. Lock-out guards are the safety net for a dynamic model

Three guards, all in `authorization` spec: built-in roles (`ADMIN`,
`OPERATOR`, `VIEWER`) are immutable; the last enabled global holder of the
`*` permission cannot be disabled, deleted, or stripped of it; nobody can
revoke their own `user:admin`/full-access grant. These are checked in
`service/RoleService`/`UserService` before the mutation, in the same
transaction as the audit write — the same "server enforces, never just the
UI" precedent as ADR-0022's bulk cap.

### 5. API tokens: SHA-256 hash, not bcrypt; intersected against live owner grants

A minted token secret is 256 bits of generated entropy, not a
human-memorable password — a slow KDF (bcrypt/argon2) buys nothing against
brute force here and would cost real latency on every API-token-authenticated
request. SHA-256 of the secret, looked up by an indexed plaintext prefix,
compared with `MessageDigest.isEqual`. A token's configured grants are
**intersected**, not just copied, with its owner's live grants at
authentication time, so demoting or disabling the owning user immediately
narrows or disables the token — no separate revocation sweep needed.

### 6. OIDC: JIT provisioning, mapping re-applied every login

`app_user.password_hash = NULL` for an OIDC-sourced account — exactly what
`003-identity.sql`'s original comment anticipated. `oidc_role_mapping` (claim
name, claim value, role, scope) is re-evaluated on **every** login rather
than only at first provisioning, so a group change at the identity provider
takes effect without any action in Studio. Local login stays available
specifically as a break-glass path if the identity provider is unreachable.

### 7. `@PreAuthorize` on services, not controllers

Several controllers share a service (`DlqController` and `MessageController`
both reach message operations). Putting the check on the controller method
would require duplicating it per route; putting it on the service method
covers every caller, including any future route. Scheduler-originated calls
into the same services carry no `SecurityContext` — Decision 8 covers how
that is kept safe rather than papered over.

### 8. Scheduler-reached service methods are never annotated with a fake system principal

`ScrapeCycle`, `AlertEvaluator`, `AlertDispatcher`, the reapers, and the RR
sweep call services with no authenticated principal. The task list requires
an explicit caller audit (tasks.md section 3) before any `@PreAuthorize` is
added, so each service method is classified as web-facing (annotate) or
scheduler-reached (leave unannotated, or split the class if a method serves
both a controller and the scheduler). Populating a synthetic "system"
`Authentication` to satisfy `@PreAuthorize` uniformly was rejected — it would
make a bug in the filter chain (e.g., a forgotten annotation) invisible,
since the fake principal would pass anything.

## Risks / Trade-offs

- **[Risk] A new controller ships without a permission check.** →
  Mitigation: `EndpointProtectionTest` reflects over every `@RequestMapping`
  method and fails closed — an unlisted, unprotected path fails the build.
- **[Risk] Every existing integration/E2E test that calls the API
  unauthenticated now fails.** → Mitigation: this is intentional (the
  proposal's BREAKING note); update test setup to authenticate (a shared
  `@WithMockUser`-equivalent or a logged-in `TestRestTemplate` helper) as
  part of the same change, not as follow-up debt.
- **[Risk] In-memory login throttling and token last-used buffering are lost
  on restart and don't coordinate across instances.** → Acceptable for a
  single-instance app; ceiling and upgrade path noted with a `ponytail:`
  comment, revisited at v1.0's multi-instance HA item.
- **[Risk] Dynamic permission strings mean a typo denies silently instead of
  failing to compile.** → Mitigation: Decision 2's catalogue plus
  `EndpointProtectionTest`.
- **[Trade-off] Session-cookie auth couples the SPA to same-origin
  deployment.** → Already true today (`SpaRoutingConfig` serves the SPA from
  the same origin as the API); no regression.

## Migration Plan

1. Land schema + persistence first (tasks.md section 1–2) with no
   enforcement — additive, no behavior change, safe to merge on its own if
   the review wants to split it (the OpenSpec change itself stays one unit
   per the proposal's stated scope).
2. Land authentication (section 3–4): from this point the app is no longer
   open, but everyone who logs in is the bootstrap admin — still a fully
   working product, per CLAUDE.md's "never trade a working product for
   unfinished code."
3. Land authorization (section 5): the caller audit runs first, then
   `@PreAuthorize` is added service-by-service; each service's own tests gate
   its slice.
4. Environments, admin UI, tokens, OIDC (sections 6–9) are additive on top of
   a working, secured app.
5. **Deployment note for the dev/prod compose stacks and any scripted
   caller**: after this change, `just up` prints the bootstrap admin
   password in the studio container's log — document this in the quick
   start (task in section 10). No data migration is needed; `014-identity.sql`
   only adds columns/tables, never alters `003`'s.
6. **Rollback**: reverting this change means reverting to an open API, which
   is a security regression, not a neutral rollback — call this out
   explicitly in the PR description rather than treating rollback as free.

## Open Questions

None — every question that would change the specs, the approach, or the task
breakdown was resolved with the user before this document was written (see
the decisions table in proposal.md's source plan).
