## 1. Design
- [x] 1.1 Brainstorm and investigate; `/opsx:update` adds design.md, sharpens specs, replaces these tasks

## 2. Core envelope (A; everything else depends on it)
- [ ] 2.1 `Keyring`, `KeyProvider`, env provider (bare key or `1=<b64>,2=<b64>`), file provider, `SecretProviderProperties` (`artemis-studio.secrets.*`); startup fails naming the provider on a missing, empty or wrong-length key
- [ ] 2.2 `SecretVault` blob format (D1): `seal(aad, plaintext)`, `open(aad, blob)`, `rewrap(blob, target)`, `kekVersion(blob)`; unknown version reloads the keyring once; unit tests (round trip, AAD binding, tamper, wrong key, rewrap keeps ciphertext, unknown version)
- [ ] 2.3 `secret_key_state` changeset + current version held in the DB (D4); startup fails if the keyring lacks the current version
- [ ] 2.4 Changesets swap each store to one `sealed bytea` and drop old ciphertext (D2): broker_credential, notification_channel, plugin_secret, message_index, rr_event detail; `SchemaBaselineDiffTest` entries
- [ ] 2.5 Move every caller to the new API (ClusterService, ClusterConnectionSettings, ClusterSecrets, NotificationChannelService, AlertDispatcher, PluginSecretStore, ContentSealer, MessageIndexWriter, MessageIndexRemasker, IndexQueryExecutor, RrPayloads) and their tests

## 3. Providers (B)
- [ ] 3.1 Vault provider on `spring-vault-core` (KV v2 versions = key versions; token, AppRole, Kubernetes auth); test against Testcontainers Vault
- [ ] 3.2 Kubernetes provider on JDK `HttpClient` (service-account token + CA); test against a stub API server
- [ ] 3.3 OIDC client secret resolved from the provider; a configured secret alongside a non-env provider fails startup

## 4. Rotation (C)
- [ ] 4.1 `secret_rotation` changeset; `SealedStore` per store (count below version, rewrap batch guarded by old bytes)
- [ ] 4.2 `SecretRotationService` start (settings:write, fresh auth, audited incl. refusals, 409 when no newer key or already running) and the INSTALLATION-scope sweep job; resume after restart; stale-writer sweep
- [ ] 4.3 `GET /api/v1/settings/secrets` status and `POST /api/v1/settings/secrets/rotations`; tests for permission, step-up, progress, resume, failure

## 5. Redaction (D)
- [ ] 5.1 `SecretRedactor` with pattern tests
- [ ] 5.2 Logback conversion rule for message and throwable; `AuditService` applies every `AuditParamsFilter`; `CredentialAuditParamsFilter`; `Problems` masks detail and title; broker.xml export backstop

## 6. UI (E)
- [ ] 6.1 Move `StepUp`, `isReauthRequired`, `useReauthenticate` into the kernel; plugins feature uses them
- [ ] 6.2 Security settings section: provider, versions, rows per version, last rotation, Rotate with step-up, polling while running, empty and error states; tests

## 7. Proof and docs (F)
- [ ] 7.1 `SecretLeakTest`: plant known secrets across clusters, bridges, channels, plugin vault; assert absence from logs, audit rows, error bodies, export
- [ ] 7.2 ADR-0132 (envelope, providers, rotation; supersedes ADR-0009's key handling) and ADR-0133 (redaction), mirrored to the site
- [ ] 7.3 Docs: configuration guide (en, zh, fa) with providers, rotation and provider-switch procedure; dockerhub; compose files; module docs regenerated

## 8. Finish
- [ ] 8.1 Reviewer pass on the full diff; findings fixed
- [ ] 8.2 `just verify` green; Security tab screenshots (light, dark, empty, running, error)
- [ ] 8.3 PR merged on green CI; change archived; roadmap ticked
