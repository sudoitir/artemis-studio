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

Each is wired by hand into one `SecurityConfig`. OIDC mappings live in an OIDC-specific table and endpoint, and a provisioned user is identified by username alone.

Directory-style integration is an expected future provider. Adding it today would mean editing the security configuration, the login endpoint, the mapping model and the login screen. Once identity lives in the kernel (ADR-0069), feature modules must not receive unrestricted access to Spring Security's configuration.

## Decision

`kernel.security::spi` defines a sealed `IdentityProvider` with three shapes:
- **`CredentialIdentityProvider`**: contributes an `AuthenticationProvider`. The kernel builds one `ProviderManager`, and `POST /api/v1/auth/login` selects a provider by `provider` (local when omitted). An unconfigured provider fails exactly like a wrong password.
- **`RedirectIdentityProvider`**: contributes `startPath` and `configure(HttpSecurity)`. Only modules of kind `IDENTITY_PROVIDER` may reference `HttpSecurity`, an ArchUnit rule.
- **`BearerIdentityProvider`**: contributes `supports(token)` and `authenticate(token)` behind one kernel bearer filter.

External providers hand an `ExternalIdentity(providerId, subject, username, email, groups)` to the kernel's `IdentityProvisioner`:
- **Users** are keyed by `(provider_id, external_subject)`, so the same subject from two providers is two accounts.
- **Group mappings** live in `identity_group_mapping(provider_id, group_name, role_id, scope…)` with a per-provider default role. They are managed at `/api/v1/identity/providers/{providerId}/group-mappings`, which replaces `/api/v1/oidc/mappings`, and re-applied at every login.

The installed providers are published publicly at `GET /api/v1/auth/providers` and in the manifest (ADR-0070). The login screen is built only from that list: a credential form, with a provider choice when there are several, and one action per redirect provider.

Local passwords (`identity-local`), API tokens (`apitokens`) and OIDC (`identity-oidc`) become three provider modules.

## Consequences

- A directory-style provider is a new module that wraps Spring Security's `LdapAuthenticationProvider` in a `CredentialIdentityProvider`, plus one composition-root line. No kernel, feature or frontend change is needed.
- **BREAKING:** the OIDC mapping endpoint and table are renamed. The schema change ships in the ADR-0072 re-baseline.
- The permission model (ADR-0038) is unchanged. Providers establish who the user is; grants still decide what they may do.
- A redirect provider still receives `HttpSecurity`. That is the one place a module touches the security framework directly, and it is limited to one module kind.

## Alternatives considered

- **Give every provider a `Customizer<HttpSecurity>`.** It is maximally flexible, but grants unrestricted framework access to any module.
- **Keep OIDC-specific mappings and add a parallel table per provider.** Mapping UI and logic would be duplicated for each new provider.
- **Key external users by username.** Two providers reporting the same username would silently share an account.
