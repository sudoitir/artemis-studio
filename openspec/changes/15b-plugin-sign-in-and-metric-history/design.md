## Context

See proposal.md for why. What exists today:

- **Sign-in** (ADR-0073). `kernel.security` defines the sealed `IdentityProvider`; modules contribute
  providers through `IdentityProviders` beans. `LoginService.login` is the one credential path: it
  audits the attempt, checks the per-username/per-source limiter, asks the named
  `CredentialIdentityProvider`, applies the account lockout, the trusted device and the second factor,
  and starts the session. `IdentityProviderCatalog` publishes the credential and redirect providers at
  `GET /api/v1/auth/providers` and in the manifest, and the login screen (`LoginView`) is built only
  from that list, with a provider choice when there are several credential providers.
- `CredentialIdentityProvider.authenticate` returns a `StudioPrincipal`, which carries the user id
  and grants. External identities go through `IdentityProvisioner.provision(ExternalIdentity)`, which
  keys users by `(provider_id, external_subject)` and re-applies the provider's group mappings.
- **Second factors** (ADR-0143) are for local accounts only: `SecondFactorService.required`,
  `MfaEnrolment.isLocal`, the account page's `TwoStepSection` (`status.local`) and the users panel
  (`providerId === 'local'`) all test for the `local` provider, on the grounds that any other
  provider "does its own MFA".
- **Revoking access** (ADR-0123): `SessionTerminator.endSessionsOf` deletes a user's sessions after
  commit and tells every replica; disabling a user also ends their trusted devices, and API tokens
  stop working for a disabled owner.
- **Plugins** contribute to host features through `PluginBridge` beans (`attach`/`detach`), finding
  their own beans with `PluginHandle.beansOfType` and calling in through `runInPlugin`.
  `PluginMetrics` is the model: a declared contribution in `plugin.json`, a bean of an `@PluginApi`
  type, a bounded call on a virtual thread. Plugins see only `@PluginApi` beans (`PluginApiContext`).
- **Trust** (ADR-0141): activation requires a trusted signer unless `allow_unverified` is on; an
  installed plugin whose key is removed keeps running, marked unverified.
- **Metric history**: `MetricQueryService.query` (queue and cluster metrics) and `pluginQuery`
  (plugin metrics) clamp the range to retention and the step to the point cap, and report it in
  `truncated`. Both check the current principal through `ClusterAccessGuard`, which answers 404 for a
  cluster the caller may not read. The service is not `@PluginApi`.
- **Acting users** (ADR-0111): plugin messaging takes an `actingUserId` and re-reads that user's
  account and grants on every use (`AccessCheck`); `OperatorHandoff` runs work as a captured operator.

## Goals / Non-Goals

**Goals:** a signed, trusted plugin can add a username-and-password sign-in that goes through
Studio's one login path, can tell Studio which of its users are gone, and can read metric history as
a named user, with nothing Studio enforces moving into plugin code.

**Non-Goals:** redirect (OIDC-like) providers from plugins; plugins changing throttling, lockout, MFA
or session rules; bearer providers from plugins; writing metric history beyond publishing metrics;
password change for plugin accounts (their password lives at the source).

## Decisions

### D1. A plugin provider answers with an identity, never a principal
New `@PluginApi` types in `kernel.security`:

```java
public interface PluginCredentialProvider {
    String id();                                   // "<plugin id>:<name>", declared in plugin.json
    Optional<VerifiedIdentity> authenticate(String username, String password);
    default Set<String> noLongerValid(Set<String> subjects) { return Set.of(); }   // D5
}
public record VerifiedIdentity(String subject, String username, String email, Set<String> groups) {}
```

Studio wraps each one in a `CredentialIdentityProvider` adapter that turns the answer into
`ExternalIdentity(providerId = the declared id, ...)` and calls `IdentityProvisioner.provision`. The
provider id is Studio's, not the plugin's answer, so a plugin can only ever sign in accounts keyed by
its own provider: a username equal to a local account's becomes a separate account (`alice@acme-ldap:corp`
when the name is taken), with only the grants its group mappings or default role give it. Throttling,
lockout, MFA, session issue, audit and mappings are unchanged because the adapter is just one more
credential provider on `LoginService`'s path.

*Alternative:* let plugins implement `CredentialIdentityProvider` directly. Rejected: it returns a
`StudioPrincipal`, so a plugin could hand back any user id and grant set, including an administrator's.

### D2. Providers are declared in `plugin.json`
`identityProviders: [{ "id": "acme-ldap:corp", "label": "Corporate directory" }]`. The validator
requires the `<plugin id>:<name>` form (the metric-name rule), unique ids and a label of 1 to 64
characters. At activation the bridge refuses a provider bean whose id is not declared, and a declared
id with no bean. The provider list, the login screen and the group-mapping screen read the
declaration, so the unauthenticated `GET /auth/providers` never calls plugin code.

### D3. One bridge holds the plugin providers
`kernel.security.internal.PluginSignIn` implements both `PluginBridge` and `IdentityProviders`.
`attach` checks D2 and records the plugin's adapters; `detach` drops them (only for the handle that
attached, as `PluginMetrics` does). `providers()` returns the adapters of attached plugins whose
handle is verified now (D4). A provider of a stopped plugin is therefore absent from the list, and a
login naming it fails exactly like a wrong password through the existing "unconfigured provider"
path. Sessions of its users are not ended on detach: an update detaches and re-attaches, and must
not sign everyone out. They end through D5, an administrator, or the session lifetimes.

### D4. Only a verified plugin may sign users in
Sign-in sees passwords, so `allow_unverified` does not cover it:
- the activation plan refuses a plugin that declares `identityProviders` unless its signer is
  trusted now (`plugin-signin-unverified`), whatever the allowance;
- a plan whose plugin adds a provider (install, or an update that declares a new one) needs the
  acknowledgement `signin-added`, and the review screen says the plugin will receive users' passwords;
- `PluginHandle.verified()` reports whether the running version's signer is trusted now (`PluginHost` hands the runtime a check over `PluginTrust` and the install row's signer); a key
  removed while the plugin runs withdraws its providers from the next login and listing at once
  (the plugin keeps running, as ADR-0141 says, but no longer signs anyone in).

### D5. Studio asks a provider which identities it no longer vouches for
An installation-wide `ScheduledJob` (`plugin-identity-revalidation`, every 5 minutes, ADR-0125) asks
each verified plugin provider, in one bounded call, `noLongerValid(subjects)` with the external
subjects of that provider's enabled accounts. For each subject it returns (ignoring ones it was not
given), Studio, in one transaction: ends the user's sessions (`SessionTerminator`), revokes their API
tokens (the `PersonalTokens` port `UserService` already uses), ends their trusted devices, and audits
`IDENTITY_REVOKED` with the provider and subject. The account is not disabled: a user restored at the
source signs in again normally, and the next `authenticate` refuses one who is not.

*Alternative:* a push API (`revoke(providerId, subject)`) a plugin calls. Rejected: it needs Studio to
know which plugin is calling a shared bean, and leaves the guarantee to every plugin author's
scheduling. The pull call is opt-in through the default method, bulk-friendly (one directory query
per tick), and timed by Studio.

### D6. Bounds and health
Every call into a provider runs through `runInPlugin` on a virtual thread: `authenticate` waits at
most 5 s, `noLongerValid` 30 s. A throw, a timeout or an invalid answer (blank subject or username,
any field over 255 characters, more than 1,000 groups) is a failed sign-in, answered and counted
exactly like a wrong password, and recorded against the provider. `PluginSignInHealthIndicator`
joins the `studio` health group and reports DEGRADED while a provider's latest call failed, naming
the provider and the reason; the next success clears it. Local sign-in never waits on a plugin.

### D7. Studio's second factor covers every password account
MFA stays Studio's (proposal), and a plugin's directory usually has none, so "local only" becomes
"an account whose provider is a credential provider": the local provider and plugin providers. A
redirect provider's users (OIDC) are still not challenged, since that provider owns authentication.
`SecondFactorService.required`, `MfaEnrolment` and the account and users screens test this through
`IdentityProviderListing` (the account's provider is listed with kind `CREDENTIAL`), instead of
`== "local"`. `MfaStatusView.local` becomes `passwordAccount`; `UserView` gains `passwordAccount`.
An account whose plugin is stopped is not a password account while it is stopped; it cannot sign
in then anyway. Password change stays local only.

### D8. Step-up for a plugin account goes to its provider
`LoginService.reauthenticate` already asks the account's credential provider. For a plugin account
the adapter is asked with the account's external username: the stored username with a trailing
`@<provider id>` qualifier removed. A step-up must identify the same user id, so the provider cannot
step up into another account.

### D9. Metric history is read through one `@PluginApi` bean as a named user
`feature.metrics.MetricHistory` (`@Component @PluginApi`):

```java
MetricSeriesResponse read(UUID actingUserId, UUID clusterId, MetricQuery query);
MetricSeriesResponse readPluginMetric(UUID actingUserId, UUID clusterId, String metric,
                                      String subject, Instant from, Instant to, Duration step);
```

It resolves the acting user through a new `OperatorHandoff.forUser(UUID)`: the account as it stands
now, with grants re-read from the database, or empty when the id is null, unknown or disabled. Empty
answers `NotFoundException("cluster", clusterId)`, the answer for a cluster that does not exist. The
read then runs through `OperatorHandoff.callAs(operator, supplier)` into the existing
`MetricQueryService.query` / `pluginQuery`, so the cluster check, the plugin metric's declared
permission, the retention clamp, the point cap and `truncated` are the ones the REST API uses. A
plugin doing background work keeps the id of the user who configured it, as plugin messaging does,
and a user who has lost access gets the not-found answer on the next read.
`MetricQuery` and the `MetricViews` records it returns become `@PluginApi`, so japicmp guards them.

*Alternative:* a new reader beside `MetricQueryService` with plugin-specific result types. Rejected: a
second copy of the clamps and checks, and a second shape for the same series the SDK charts already
draw.

### D10. Contract version
All of this adds types, methods and an optional descriptor field. `Contract.VERSION` is bumped only
if japicmp flags a break in the build. `MfaStatusView.local` → `passwordAccount` changes the REST shape,
which the oasdiff gate (ADR-0149) will flag: its commit is marked breaking.

## Risks / Trade-offs

- A malicious but trusted plugin sees passwords typed for its provider. That is inherent to credential
  sign-in; D4 makes it an explicit, signed, acknowledged decision.
- `noLongerValid` sends every enabled subject of the provider in one call. Fine for directories in
  the tens of thousands; batch the call if one ever times out (`ponytail:` note in the job).
- Revocation is up to 5 minutes behind the source, and pauses while the plugin is stopped.
- A plugin may name any user as acting user (as in plugin messaging, ADR-0111). Plugins are trusted
  code; the API keeps them honest about whose permissions apply, it does not sandbox them.

## Decisions made while applying

- **`PluginHandle.verified()`** asks `PluginTrust.verified(pluginId)`, which reads the install row's signer
  and decides on every call, rather than the host handing each runtime a check: the runtime already holds the
  main context, and a runtime activated outside the host (tests) is simply not verified.
- **The revalidation job lives in the plugins module.** `kernel.jobs` depends on `kernel.security`, so a job
  cannot be declared in the security module. `PluginIdentityRevalidation` (public, `kernel.security`) does the
  work and `feature.plugins.PluginIdentityJobs` schedules it. Each revocation runs in its own transaction, as
  the system actor; `SessionTerminator.endSessionsOf` became public for it.
- **`SecondFactorService` finds `IdentityProviderListing` lazily.** The providers include the API-token one,
  which needs the second factors, so injecting the listing directly is a bean cycle.
- **Local is listed first.** `IdentityProviderCatalog` sorts the local provider first, so the login screen's
  default (the first credential provider) is never a plugin's sign-in, whatever order modules were wired in.
- **`GET /auth/me` gains `providerId`**, so the account page offers Change password only to a local account
  and tells everyone else where their password is kept. The login screen also says when the list of sign-in
  methods could not be loaded.
- **Known limit.** A directory that gives a new subject the username of an existing plugin account whose
  `name@<provider id>` form is also taken makes the unique constraint fail at provisioning, which surfaces as
  a conflict rather than a wrong password. Directories that reuse names for different subjects should not.
