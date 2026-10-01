# ADR-0153: Plugins sign users in through declared credential providers that answer with an identity

- **Status**: accepted
- **Date**: 2026-10-01
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/15b-plugin-sign-in-and-metric-history`
- **Builds on**: [ADR-0073](0073-identity-provider-spi.md), [ADR-0113](0113-plugins-publish-metrics-through-metric-sources.md), [ADR-0123](0123-revoking-access-ends-sessions.md), [ADR-0141](0141-plugin-jars-are-signed-and-verified-against-pinned-keys.md)
- **Amends**: the "local accounts only" scope of [ADR-0143](0143-second-factors-for-local-accounts.md)

## Context

Identity providers are a kernel contract (ADR-0073) that only built-in modules can contribute. A
runtime plugin cannot add a sign-in, so directory or other credential sign-in needs a fork.

`CredentialIdentityProvider.authenticate` returns a `StudioPrincipal`: a user id and its grants. Handed
to plugin code, that would let a plugin sign anyone in as anyone. Second factors (ADR-0143) apply to
the `local` provider only, on the grounds that any other provider does its own MFA; a directory that a
plugin checks passwords against usually does not.

## Decision

1. **A new `@PluginApi` interface, `PluginCredentialProvider`**: `id()`, `authenticate(username,
   password)` returning `Optional<VerifiedIdentity(subject, username, email, groups)>`, and a default
   `noLongerValid(subjects)` that returns nothing.
2. **Declared, like metrics.** `plugin.json` `identityProviders` lists `{id, label}`, with ids
   `<plugin id>:<name>`. The provider list and the login screen read the declaration, so the
   unauthenticated provider list never calls plugin code. A bean that is not declared, or a
   declaration with no bean, refuses activation.
3. **Studio owns everything but the answer.** A bridge in `kernel.security` (`PluginSignIn`, a
   `PluginBridge` and an `IdentityProviders` contribution) wraps each provider as a
   `CredentialIdentityProvider` on the one login path. It maps the answer to an `ExternalIdentity`
   whose provider id is the declared id, and provisions it by provider and subject. Throttling,
   lockout, second factors, session issue, audit and group mappings are unchanged.
4. **Bounded and observable.** `authenticate` waits at most 5 s on a virtual thread through
   `runInPlugin`. A throw, a timeout or an invalid answer is a wrong password to the caller, and marks
   the provider degraded in the `studio` health group until its next success.
5. **Verified plugins only.** A plugin that declares a provider activates only with a trusted signer,
   even when unverified plugins are allowed. A version that adds a provider needs the
   `signin-added` acknowledgement. A provider is offered only while its plugin's signer is trusted
   now, so removing a key stops its sign-in at once.
6. **Studio asks who is gone.** An installation-wide job asks each provider every 5 minutes, in one
   call bounded to 30 s, which subjects of its enabled accounts it no longer vouches for. For each,
   Studio ends the sessions, revokes the API tokens and trusted devices, and audits
   `IDENTITY_REVOKED`. It does not disable the account, so a user restored at the source signs in again.
7. **Studio's second factor covers every password account.** MFA enrolment and per-role requirement
   apply to the users of every credential provider, local or plugin. Redirect providers' users are
   still not challenged.

## Consequences

- A directory sign-in is a plugin, with no fork and no kernel change.
- A plugin can only sign in accounts keyed by its own provider id. A username that collides with a
  local account becomes `name@<provider id>` with its own grants.
- A trusted plugin sees the passwords typed for its provider. That is inherent to credential sign-in,
  and installing one is now an explicit, signed and confirmed decision.
- Deactivating a plugin removes its provider but keeps its users' sessions, so an update does not
  sign everyone out. Revocation is up to 5 minutes behind the source and pauses while the plugin is
  stopped.
- **BREAKING (REST):** the second-factor status field `local` is renamed `passwordAccount`.

## Alternatives considered

- **Let plugins implement `CredentialIdentityProvider`.** It returns a principal, so a plugin could
  claim any user and any grants.
- **A push API for revocation.** Studio would have to tell which plugin calls a shared bean, and the
  guarantee would depend on every plugin's own scheduling.
- **Keep second factors local-only.** A plugin's password would then be the only factor even for
  administrators, which makes plugin sign-in weaker than local sign-in.
- **Honour `allow_unverified` for sign-in plugins.** The allowance is for trying plugins out; one
  that receives passwords should never run unverified.
