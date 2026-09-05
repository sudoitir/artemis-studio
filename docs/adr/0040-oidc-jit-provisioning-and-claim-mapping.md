# ADR-0040: OIDC — JIT provisioning, principal swap after the exchange, claim mapping re-applied every login

- **Status**: accepted
- **Date**: 2026-09-05
- **Deciders**: Mahdi Amirabdollahi

## Context

Phase 8's roadmap line calls for OIDC/SSO with just-in-time (JIT) user
provisioning and a claim → role mapping table, local login staying available
as a break-glass path. `003-identity.sql`'s `app_user.password_hash` was
already nullable, anticipating an OIDC-sourced account with no local
password.

Everything downstream of authentication — `@PreAuthorize`'s `@perm` bean,
`ActorResolver`, the audit trail — expects a `StudioPrincipal`. Spring
Security's OIDC login hands back an `OidcUser`, a different principal shape
entirely.

## Decision

**No custom `OAuth2UserService`/`OidcUserService`.** This is a deliberate
deviation from the original sketch. `security/oidc/StudioOidcUserService` is
a plain `@Component`, not a hook into Spring Security's OIDC user-loading
pipeline. It does JIT provisioning (issuer + subject, `password_hash = NULL`,
`auth_source = OIDC`) and is called from
`security/oidc/OidcAuthenticationSuccessHandler` **after** Spring Security
has already completed the authorization-code exchange and produced an
`OidcUser`. That handler then replaces the `OidcUser` principal in the
`SecurityContext` with a `StudioPrincipal`, so every downstream check sees
the same principal shape regardless of how the caller authenticated — local
session, OIDC, or API token (ADR-0039).

`config/SecurityConfig` calls `.oauth2Login()` only when a
`ClientRegistrationRepository` bean actually exists
(`ObjectProvider<ClientRegistrationRepository>.getIfAvailable()`), since Boot
creates none at all with no
`spring.security.oauth2.client.registration.*` configured, and the DSL would
otherwise fail to wire. This makes OIDC genuinely opt-in with zero
configuration cost when unused.

`security/oidc/ClaimRoleMapper` reads `oidc_role_mapping` (claim name, claim
value, role, scope) and **reconciles on every login**, not only at first
provisioning: it adds newly-matched grants, removes grants that match a
mapping row but no longer match the current claim values, and leaves every
other (hand-granted) grant alone. A user matching no mapping gets the
configured default role, or is refused with a clear reason if none is
configured. A `ponytail:` comment on the class names the one inference limit
this carries: a grant is treated as "OIDC-derived" if its (role, scope)
exactly matches a currently-configured mapping row — there is no separate
"derived by OIDC" flag on `user_role`, so an administrator who hand-grants a
user exactly what a mapping would also grant is indistinguishable from the
mapping itself.

A public, unauthenticated `GET /api/v1/auth/providers` endpoint lists
configured registrations (id, label, authorization URL) so the login screen
can show an SSO entry point before any session exists —
`ClientRegistrationRepository`'s base interface has no listing method, so
this reads via the `Iterable` the Boot auto-configured in-memory
implementation also happens to implement.

Local login stays available specifically as a break-glass path if the
identity provider is unreachable.

## Consequences

- The full authorization-code flow is **not** end-to-end tested — no test
  IdP is practical in this suite. `ClaimRoleMapperTest` and
  `StudioOidcUserServiceTest` cover the reconciliation and provisioning logic
  against mocked repositories and a stubbed `OidcUser`; this is stated
  explicitly rather than overclaimed.
- RP-initiated OIDC logout is out of scope — local session logout still
  works; the identity provider's own session is untouched. Add if asked for.
- DB-configured OIDC providers are out of scope — a provider is configured
  via `application.yml`/environment only, with its client secret expected
  from the environment, never committed.

## Alternatives considered

- **A custom `OidcUserService` overriding `loadUser`.** Rejected: it only
  has access to an `OidcUser`, not the full request context
  `OidcAuthenticationSuccessHandler` has, and would still need a second step
  to swap the principal shape for every downstream check to work uniformly —
  doing the swap once, after the exchange, is simpler than doing it inside
  the hook and again in a success handler.
- **Mapping claims to roles only at first provisioning.** Rejected: a group
  change at the identity provider would silently not take effect until an
  administrator manually re-synced the user — re-applying on every login
  costs one extra query and removes that entire class of drift.
