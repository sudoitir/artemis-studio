## Context

`SecretVault` (ADR-0009) seals every secret with one AES-256-GCM key read from
`ARTEMIS_STUDIO_SECRET_KEY`, binding each ciphertext to its row through the additional
authenticated data. Five stores hold its output as a `(ciphertext, nonce)` pair:

| Store | Columns today | Written by |
| --- | --- | --- |
| `broker_credential` (cluster credentials, bridge `REF:` credentials) | `secret_ct`, `secret_nonce` | `ClusterService`, `ClusterSecrets` |
| `notification_channel` | `secret_ct`, `secret_nonce` | `NotificationChannelService` |
| `plugin_secret` | `ciphertext`, `nonce` | `PluginSecretStore` |
| `message_index` (governance originals) | `sealed`, `sealed_nonce` | `ContentSealer` via `MessageIndexWriter` |
| `rr_event.detail` jsonb | base64 `sealed`, nonce | `ContentSealer` via `RrPayloads` |

The key cannot be rotated and can come only from the environment. Nothing redacts
credential-like values from logs, audit parameters or error details except where a caller
remembered to leave them out. The OIDC client secret is not stored; it is read from
`ARTEMIS_STUDIO_OIDC_CLIENT_SECRET`.

## Goals / Non-Goals

**Goals**
- Envelope encryption for all five stores, in one format.
- KEK rotation that is online, resumable, safe with several replicas, and visible.
- Key providers: env (the default), file, HashiCorp Vault, and Kubernetes Secrets. The provider
  also supplies the OIDC client secret.
- Redaction at the choke points, with a test that proves it.

**Non-Goals**
- HSM or KMS integration.
- Vault Transit.
- Third-party provider plugins.
- Rotating credentials at the broker or IdP.
- Migrating existing ciphertext.

## Decisions

### D1: One self-describing sealed blob
`SecretVault.seal(aad, plaintext) → byte[]` and `open(aad, blob) → String` replace `Sealed` and
the cluster/kind overloads. The layout is:

```
0x01 | kekVersion (int32) | wrapNonce (12) | wrappedDek (32 + 16 tag) | nonce (12) | ciphertext + tag
```

- Each seal draws a fresh AES-256 DEK and encrypts the plaintext with AES-GCM under the caller's
  AAD. This keeps the row binding of ADR-0009.
- The DEK is wrapped with AES-GCM under KEK `kekVersion`. The wrap uses its own AAD,
  `"dek|" + kekVersion`, so a wrapped key cannot be relabelled to another version.
- `rewrap(blob, targetVersion)` unwraps the DEK and wraps it again, copying the ciphertext bytes
  unchanged. Rotation needs no row AAD and never sees plaintext.
- The JDK only, as in ADR-0009.
- Alternative rejected: a shared `data_key` table referenced by id. Rotation would be cheaper, but
  every read would need a join, and the spec requires one data key per secret.

### D2: Every store keeps one `sealed bytea` column
- Liquibase drops the old pair and adds `sealed`. Existing ciphertext is dropped (BREAKING):
  - `broker_credential` rows are deleted;
  - notification channel secrets are nulled;
  - `plugin_secret` rows are deleted;
  - governance originals are nulled.
- The release note tells operators to re-enter broker credentials, channel secrets and plugin
  secrets.
- This follows the standing decision against migrations before the stable release.
- `rr_event.detail` stores the blob in base64 under `sealed`.

### D3: A keyring from one provider, selected by configuration
- `KeyProvider` has four implementations, selected by `artemis-studio.secrets.provider`:
  `env` (the default when unset), `file`, `vault` or `kubernetes`.
- Each one returns a `Keyring`, a map of version → 32-byte key, and
  `Optional<String> secret("oidc-client-secret")`.
- The implementations:
  - **env:** `ARTEMIS_STUDIO_SECRET_KEY` is either a bare base64 key (version 1) or
    `1=<b64>,2=<b64>`. The OIDC secret comes from `ARTEMIS_STUDIO_OIDC_CLIENT_SECRET`.
  - **file:** `artemis-studio.secrets.file.directory` holds `kek-<n>` files (base64) and
    `oidc-client-secret`. A mounted Kubernetes Secret volume fits this layout.
  - **vault:** `spring-vault-core`, KV v2 at `artemis-studio.secrets.vault.path`, field `kek`.
    Every non-destroyed KV version is a KEK version, and the field `oidc-client-secret` is read
    from the latest version. Authentication is by token, AppRole or Kubernetes.
  - **kubernetes:** the JDK `HttpClient` reads `/api/v1/namespaces/{ns}/secrets/{name}` with the
    pod's service-account token and CA (in-cluster paths, configurable). It uses keys `kek-<n>`
    and `oidc-client-secret`. No new dependency.
- If a provider cannot deliver a valid keyring at startup (missing, unreachable, empty, or a key
  that is not 32 bytes), the application fails to start with a message naming the provider.
  There is no fallback provider.
- When OIDC is enabled and the provider has the secret, it becomes the registration's client
  secret. An explicitly configured `artemis-studio.security.oidc` client secret is an error when
  the provider is not `env`, so the secret has exactly one source.

### D4: The current version is stored, not inferred
- `secret_key_state` is a single row: `current_kek_version`, `provider`, `updated_at`.
- On the first start after install it is set to the highest keyring version. A version that
  appears later is used only once a rotation adopts it, so every replica wraps with the same
  version whatever order they restart in.
- `seal` wraps with the stored version. It is cached, and the cache is refreshed when a rotation
  starts or when a blob carries an unknown version.
- `open` with a version missing from the keyring reloads the provider once, then fails with
  `SecretDecryptException` naming the version, never the key.
- Startup fails when the keyring lacks the stored current version.

### D5: Rotation
- `POST /api/v1/settings/secrets/rotations` requires `settings:write` and a fresh authentication
  (`SessionAuthentication.recentlyAuthenticated`, ADR-0103). A refused or failed start is audited
  like a successful one.
- The start reloads the keyring and takes the highest version as its target. It fails with 409
  when the target is not newer than the current version or when a rotation is already running.
- In one transaction, the start writes a `secret_rotation` row
  (`id`, `from_version`, `to_version`, `status`, `started_by`, `started_at`, `finished_at`,
  `rewrapped`, `remaining`, `error`) and sets `current_kek_version` to the target. New writes
  use the new key immediately.
- An INSTALLATION-scope job (`kernel/jobs`, ShedLock, ADR-0125) runs a pass every few seconds
  while a rotation is `RUNNING`. Only one replica runs it.
  - Each store contributes a `SealedStore` bean: `count(below)` and
    `rewrapBatch(below, target, limit)`, with row updates guarded by the old bytes.
  - A pass re-wraps batches until every store counts zero rows with a version below the target,
    then marks the rotation `SUCCEEDED`.
  - Because the sweep is the condition, a replica that wrote with a stale version before its
    cache refreshed is swept too, and a restart simply resumes.
  - A blob that cannot be unwrapped marks the rotation `FAILED` with the store and row id. Old
    keys must stay in the provider until a rotation succeeds, and the procedure says so.
- `GET /api/v1/settings/secrets` needs `settings:read`. It returns the provider, the current
  version, the available versions (numbers only), the counts per version, and the last rotation.
- **Provider switch (documented):** put the current key into the new provider under a version
  number at least as high, switch the configuration and restart, then rotate to a new version
  in the new provider.

### D6: Redaction at the choke points
`SecretRedactor` in `kernel.core` (so `Problems` can use it) masks two kinds of credential-like values:
- values of keys matching `password|passwd|pwd|secret|token|api[-_]?key|authorization|credential|private[-_]?key`,
  whether in `key=value`, `key: value` or JSON form;
- whole values: `Bearer …`, user-info in URLs, PEM private keys, and Studio API tokens (by
  prefix).

It is applied at four choke points:
- **Logs:** a `logback-spring.xml` conversion rule replaces `%msg` and `%ex` in the console and
  file patterns with masked versions.
- **Audit:** `CredentialAuditParamsFilter` masks values whose parameter name is credential-like,
  and strings that match. `AuditService` applies every `AuditParamsFilter` bean in order, not
  just one.
- **Errors:** the `Problems` factory masks every `detail` and `title` it builds, which covers
  every advice.
- **Export:** the broker.xml export passes through the redactor as a backstop to ADR-0092.

The leak test is the proof: it plants known secrets and checks every one of these outputs.

### D7: UI
- A "Security" section is contributed to `settings.sections`. It shows:
  - the provider, the current key version and the available versions;
  - the rows per version;
  - the last rotation, with its progress and result.
- "Rotate key" is enabled when a newer version exists and the user has `settings:write`. It
  goes through step-up.
- `StepUp.tsx`, `isReauthRequired` and `useReauthenticate` move from the plugins feature into
  the kernel so both features share them.
- The view refetches every 2 s while a rotation runs.
- It has an empty state (no rotation yet) and an error state (a failed load, or a rotation that
  failed with its error). It never shows key material.

## Risks / Trade-offs

- **Rotation cost.** Rotation rewrites every sealed row, including `message_index`. This is
  online and batched but proportional to the index size. It is accepted in exchange for one
  format and one data key per secret.
- **Dropped secrets.** Existing secrets are dropped on upgrade. This is stated in the release
  note, with re-entry as the recovery path.
- **Removing an old key too early** makes its rows unreadable. The status view shows the rows
  per version so an operator can see when an old version is unused, and the documentation says
  to keep old keys until a rotation succeeds.
- **The redactor is pattern-based** and can miss a new shape. The leak test pins the known paths,
  and ADR-0092's explicit paths stay.

## ADRs

- ADR-0132 covers envelope encryption, key providers and rotation. It supersedes the key-handling
  decision of ADR-0009.
- ADR-0133 covers redaction at the choke points.
