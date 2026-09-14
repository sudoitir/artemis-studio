# ADR-0073: Identity is a provider SPI; external identities are provisioned by provider and subject

- **Status**: accepted
- **Date**: 2026-09-13
- **Deciders**: Mahdi Amirabdollahi
- **Supersedes**: the mapping model of [ADR-0040](0040-oidc-jit-provisioning-and-claim-mapping.md) (JIT provisioning and re-applying mappings every login remain)
- **Extends**: [ADR-0037](0037-session-cookie-authentication.md), [ADR-0039](0039-api-tokens-sha256-intersected-grants.md)
- **Depends on**: [ADR-0069](0069-kernel-plugin-modular-monolith.md)

## Context

Studio authenticates three ways today:
- local username and password (ADR-0037);
- API bearer tokens (ADR-0039);
- OIDC with JIT provisioning and claim-to-role mapping (ADR-0040).

Each was wired by hand into one `SecurityConfig`, which imported the three modules' classes. OIDC mappings live in an OIDC-specific table and endpoint, and a provisioned user is identified by username alone.

Directory-style integration is an expected future provider. Adding it would have meant editing the security configuration, the login endpoint, the mapping model and the login screen. Once identity lives in the kernel (ADR-0069), feature modules must not receive unrestricted access to Spring Security's configuration.

## Decision

`kernel.security` defines a sealed `IdentityProvider` (`id`, `label`) with three shapes:
- **`CredentialIdentityProvider`**: `authenticate(username, password)` returns the principal, or nothing when the credentials do not match. It only answers; the kernel's one login path owns the rest.
- **`RedirectIdentityProvider`**: `startPath`, where browser sign-in elsewhere begins.
- **`BearerIdentityProvider`**: `authenticate(token)` returns the principal, or nothing when the token is not its own.

A module contributes its providers through one **`IdentityProviders`** bean: `providers()`, asked each time so a provider may depend on configuration, and `configure(HttpSecurity)`, a no-op unless redirect sign-in needs something in the chain.

The kernel assembles everything from those contributions:
- **Login.** `POST /api/v1/auth/login` names a credential provider (`local` when omitted). The kernel throttles by username and source, audits the attempt, asks the provider and starts the session. An unconfigured provider fails exactly like a wrong password, so a login request cannot probe which providers exist.
- **Bearer requests.** One kernel filter asks the bearer providers.
- **The chain.** Each contribution's `configure(HttpSecurity)` is applied once. Only redirect sign-in uses it.
- **Audit.** The login path records sign-in and sign-out through an `AuthenticationAudit` SPI that the audit module implements, because audit depends on security and not the reverse.
- **The provider list.** The configured credential and redirect providers are published publicly at `GET /api/v1/auth/providers` and in the manifest (ADR-0070). Bearer providers are for automation and are not listed. The login screen is built only from that list: a credential form, with a provider choice when there are several, and one action per redirect provider.

Local passwords (`identity-local`), API tokens (`apitokens`) and OIDC (`identity-oidc`) are three provider modules. Throttling and the must-change-password gate belong to every sign-in, so they live in the kernel's session path, not in `identity-local`.

External providers hand an `ExternalIdentity(providerId, subject, username, email, groups)` to the kernel's `IdentityProvisioner`:
- **Users** are keyed by `(provider_id, external_subject)`, so the same subject from two providers is two accounts.
- **Group mappings** live in `identity_group_mapping(provider_id, group_name, role_id, scope…)` with a per-provider default role. They are managed at `/api/v1/identity/providers/{providerId}/group-mappings`, which replaces `/api/v1/oidc/mappings`, and re-applied at every login.

Provisioning by provider and subject needs those columns, so it lands with the ADR-0072 re-baseline.

## Consequences

- A directory-style provider is a new module whose `CredentialIdentityProvider` wraps Spring Security's `LdapAuthenticationProvider`, plus one composition-root line. No kernel, feature or frontend change is needed.
- **BREAKING:** `GET /api/v1/auth/providers` now returns `id`, `kind`, `label` and `startPath`, and lists the local provider. The OIDC mapping endpoint and table are renamed; the schema change ships in the ADR-0072 re-baseline.
- The permission model (ADR-0038) is unchanged. Providers establish who the user is; grants still decide what they may do.
- An identity module still receives `HttpSecurity` through `configure`. That is the one place a module touches the security framework directly, and only redirect sign-in needs it.

## Alternatives considered

- **A Spring `AuthenticationProvider` per credential provider behind one `ProviderManager`.** The login body is JSON and names its provider, so dispatch is a lookup by id; a manager adds a second selection mechanism, and the throttle and audit sequence would still sit outside it.
- **One bean per provider instead of a contribution.** OIDC providers are one per client registration, known only from configuration, so a module could not declare them as beans.
- **Give every provider a `Customizer<HttpSecurity>`.** It is maximally flexible, but grants unrestricted framework access to any module.
- **Keep OIDC-specific mappings and add a parallel table per provider.** Mapping UI and logic would be duplicated for each new provider.
- **Key external users by username.** Two providers reporting the same username would silently share an account.
