## 1. Design
- [x] 1.1 Brainstorm and investigate; `/opsx:update` adds design.md, sharpens specs (identity-and-sessions, plugin-metrics, plugin-trust) and replaces these tasks; ADR-0153 and ADR-0154

## 2. Metric history for plugins (D9) — independent of sections 3-6, do it first
- [x] 2.1 `OperatorHandoff`: add `Optional<Operator> forUser(UUID userId)` (empty for null, unknown or disabled; grants re-read through `GrantLoader`; actor `Actor(username, null, null, userId)`) and `<T> T callAs(Operator, Supplier<T>)` beside `runAs`. Extend `OperatorHandoffTest`: unknown, disabled, grant removed since, value returned and thread left as it was
- [x] 2.2 `feature/metrics/MetricHistory` (`@Component @PluginApi`): `read(actingUserId, clusterId, MetricQuery)` and `readPluginMetric(actingUserId, clusterId, metric, subject, from, to, step)`; empty `forUser` → `NotFoundException("cluster", clusterId)`; otherwise `callAs` into `MetricQueryService.query` / `pluginQuery`. Mark `MetricQuery` and each `MetricViews` record (`MetricPoint`, `MetricSeries`, `MetricNodeSeries`, `MetricSeriesResponse`) `@PluginApi`
- [x] 2.3 `MetricHistoryIntegrationTest` (Postgres): queue depth read by a user with cluster read; another plugin's metric with and without its declared permission; no grant on the cluster, unknown cluster and disabled user give the identical not-found; a `from` older than retention comes back clamped with `truncated=true`; a grant removed between two reads (background case) turns the second read into not-found
- [x] 2.4 Plugin reach: assert in an existing plugin-context test (or `PluginApiContext` test) that `MetricHistory` is visible to a plugin context

## 3. Plugin sign-in contract (D1, D2)
- [x] 3.1 `kernel.security`: `@PluginApi` `PluginCredentialProvider` (`id()`, `authenticate(username, password)`, default `noLongerValid(Set<String>)` returning empty) and `@PluginApi` record `VerifiedIdentity(subject, username, email, groups)`, with Javadoc stating what Studio owns
- [x] 3.2 Descriptor: `identityProviders: [{id, label}]` in `PluginDescriptor` (record `IdentityProvider`, null → empty), `plugin.schema.json`, and `PluginValidator.checkIdentityProviders` (`<plugin id>:<name>` with the metric-name rule, unique, label 1-64 chars). Validator tests for each violation
- [x] 3.3 `ContributionDiff`: `identityProvidersAdded` / `identityProvidersRemoved`, filled for installs and updates

## 4. Trust gate (D4)
- [x] 4.1 `PluginHost.trustOf`: a plugin that declares `identityProviders` is allowed only when `TRUSTED`, regardless of `allowUnverified`; `beginActivation` refuses it as `plugin-signin-unverified` with a reason naming the provider; `trustedForRestart` applies the same rule
- [ ] 4.2 Acknowledgement `signin-added` when `identityProvidersAdded` is non-empty; `ActivationPlan`/`PluginAdminViews` Javadoc lists it; `web/src/features/plugins/words.ts` explains it ("This plugin will receive the passwords users type to sign in with <labels>")
- [x] 4.3 `PluginHandle.verified()`: `PluginHost` hands `PluginRuntime` a check over `PluginTrust.decide` and the install row's signer, evaluated per call
- [x] 4.4 Tests (`PluginHost`/trust integration test): unsigned sign-in plugin with the allowance on is refused; trusted install without `acknowledge` → 409 with `signin-added`; with it, activates

## 5. Login path (D3, D6, D8)
- [x] 5.1 `kernel.security.internal.PluginSignIn implements PluginBridge, IdentityProviders`: `attach` matches beans of `PluginCredentialProvider` to the declaration (undeclared bean or declared id without bean fails activation, naming the provider); `detach` only for the handle that attached; `providers()` returns adapters of attached plugins whose `handle.verified()` is true
- [x] 5.2 The adapter (`CredentialIdentityProvider`): id and label from the declaration; calls `authenticate` through `runInPlugin` on a virtual thread, 5 s bound; validates the answer (non-blank subject and username, fields ≤ 255 chars, ≤ 1,000 groups); maps to `ExternalIdentity(declared id, …)` → `IdentityProvisioner.provision`; any throw, timeout or invalid answer → empty, recorded against the provider; a success clears it. Step-up: strip a trailing `@<provider id>` from the username before asking the plugin
- [x] 5.3 `LoginService.lockableAccount`: for a provider other than `local`, also match the account of that provider whose username is `<typed>@<provider id>`
- [x] 5.4 `PluginSignInHealthIndicator` in the `studio` health group (model: `PluginTrustHealthIndicator`): DEGRADED while a provider's latest call failed, naming provider and reason
- [x] 5.5 `PluginSignInIntegrationTest` (Postgres, signed test plugin via `PluginJarBuilder`): sign-in issues a session and applies a group mapping; wrong password is identical to a local wrong password and counts toward lockout; a username equal to a local admin's becomes `name@<provider>` with only mapped grants; a throwing and a sleeping provider fail like a wrong password, health goes DEGRADED, local sign-in still works, and recovery clears it; deactivate → gone from `/auth/providers` and login fails like a wrong password; key removed → same; undeclared bean refuses activation; step-up through the provider succeeds for the same account only

## 6. Revocation (D5)
- [ ] 6.1 `AppUserRepository`: enabled accounts of a provider (subject and id)
- [ ] 6.2 `PluginIdentityRevalidation` `ScheduledJob` (`plugin-identity-revalidation`, `INSTALLATION`, every 5 min): per attached, verified provider, one `noLongerValid` call through `runInPlugin`, 30 s bound (failure recorded like 5.2); for each returned subject that was asked: end sessions (`SessionTerminator`), revoke API tokens (`PersonalTokens.revokeAllOf`), revoke trusted devices, audit `IDENTITY_REVOKED` with provider and subject; account stays enabled. `ponytail:` note on the single unbatched call
- [ ] 6.3 Tests: a revoked user's next session request and API token are unauthenticated and the audit row exists; a subject not asked about is ignored; a restored user signs in again into the same account; a provider without the method changes nothing

## 7. Second factors for every password account (D7)
- [ ] 7.1 Backend: `SecondFactorService.required`, `MfaEnrolment.isLocal` → "the account's provider is listed as `CREDENTIAL` by `IdentityProviderListing`"; `SecondFactors` Javadoc; `MfaStatusView.local` → `passwordAccount`; `UserView` gains `passwordAccount`
- [ ] 7.2 Frontend: `TwoStepSection` reads `passwordAccount`; `UsersPanel.TwoStepStatus` uses `user.passwordAccount`; `PasswordSection` stays local only (copy: "Local accounts only; other accounts change their password where they sign in"); regenerate `schema.d.ts`; update their tests
- [ ] 7.3 Tests: a plugin-provider user with a required role is sent to enrolment, enrols TOTP, then signs in with password + code; an OIDC user is still not challenged

## 8. Docs, contract, example
- [ ] 8.1 `site/src/guide/plugins.md`: "Sign-in providers" (declare, implement, what Studio owns, trust and confirmation, revalidation, MFA) and "Reading metric history" (acting user, background work, not-found answers, `MetricHistory` may be absent when the metrics feature is off: inject `ObjectProvider`)
- [ ] 8.2 Plugin template: a commented example of `MetricHistory` use in the existing metric example; no sign-in example in the template (it would ship a provider to everyone who copies it)
- [ ] 8.3 Run japicmp (`./mvnw verify` profile that runs it); bump `Contract.VERSION` (and `CONTRACT` in `web/src/kernel/feature.ts`) only if it flags a break. Commit 7.1's REST rename as breaking (`feat(auth)!:` with `BREAKING CHANGE:` naming `passwordAccount`)

## 9. Finish
- [ ] 9.1 `just verify` green; Studio run with the test sign-in plugin: login screen with the plugin provider, the enrolment screen for its user, and the plugin review showing `signin-added`, light and dark, plus the degraded health state
- [ ] 9.2 `reviewer` pass on the sign-in diff (Execution); PR, SonarQube gate green, merge on green CI; `/opsx:archive`; tick `ROADMAP.md`
