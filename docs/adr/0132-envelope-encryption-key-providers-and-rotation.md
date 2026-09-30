# ADR-0132: Envelope encryption, key providers and online key rotation

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Artemis Studio maintainers

## Context

ADR-0009 sealed every stored secret with one AES-256-GCM key read from
`ARTEMIS_STUDIO_SECRET_KEY`. It said rotation would need its own ADR when it was needed, and it
is needed now:

- The key cannot be rotated without losing every secret.
- It can only come from the environment, so operators with a secret manager cannot use it.
- Every secret sits directly under it.

By now five stores hold its output:
- broker credentials, including bridge credentials (ADR-0092);
- notification channel secrets;
- the plugin vault (ADR-0111);
- governance-sealed message originals in the message index;
- governance-sealed request-reply details.

## Decision

**Envelope encryption, one format.**
- Every secret is sealed with its own random AES-256 data key, using AES-GCM under the row's
  additional authenticated data. That row binding is unchanged from ADR-0009.
- The data key is wrapped with AES-GCM under a *key-encryption key* (KEK) identified by an
  integer version. The wrap's own AAD names that version.
- The result is one self-describing value:
  `0x01 | kekVersion | wrapNonce | wrappedDek | nonce | ciphertext`.
- Each store keeps it in a single `sealed` column; request-reply details keep it in base64 in
  their JSON.
- The JDK is still the only crypto dependency.

**Keys come from one provider, chosen by configuration.**
- `artemis-studio.secrets.provider` is one of:
  - `env` (the default when unset): `ARTEMIS_STUDIO_SECRET_KEY` is one base64 key (version 1) or
    `1=<b64>,2=<b64>`;
  - `file`: a directory of `kek-<n>` files;
  - `vault`: HashiCorp Vault KV v2 through `spring-vault-core`, where each KV version is a KEK
    version;
  - `kubernetes`: a Secret read through the API with the pod's service account, using the JDK
    `HttpClient`.
- The provider also supplies the OIDC client secret, so an operator with a secret manager keeps
  every Studio secret there.
- A provider that cannot deliver a valid key stops startup and is named in the message. There is
  no fallback.

**The current version is stored.**
- `secret_key_state` records the version that new secrets are wrapped with. It is set on the
  first start and changed only by a rotation.
- A key version that appears in the provider is not used until a rotation adopts it, so replicas
  restarting in any order agree.

**Rotation is online and resumable.**
- An administrator with `settings:write` and a fresh authentication (ADR-0103) starts it.
- The rotation adopts the provider's highest version as current, so new writes use it at once.
- An installation-scope job under ShedLock (ADR-0125) then re-wraps each store's data keys in
  batches, walking each store by primary key so a pass converges cheaply and completely. It
  never touches ciphertext or plaintext.
- It finishes only when no row in any store is under an older version and 30 seconds have passed
  since the start, which is longer than a replica takes to learn the new current version. That
  condition makes it resumable after a restart, and it sweeps up rows a stale replica wrote
  meanwhile.
- A blob that cannot be unwrapped fails the rotation with the store and row. A failed rotation
  can be started again: it re-wraps what is still under an older version and keeps the current
  one, and only a rotation that is already running is refused.
- While it runs, the status shows the rows still under an older version from the moment it starts.

## Consequences

- A leaked KEK version can be retired by rotating, and only once all rows have moved off it.
  The status view counts rows per version, so an operator knows when an old key can be removed.
- Rotation cost grows with the message index, which is the largest store. It runs online in
  batches.
- This version drops the secrets stored in ADR-0009's format. Operators re-enter broker, channel
  and plugin secrets. There is no migration, by the standing decision against backward
  compatibility before the stable release.
- `spring-vault-core` (4.0.3, managed by the Spring Cloud BOM) is a new dependency, used only by
  the Vault provider.
- ADR-0009's key-handling decision is superseded. Its broker TLS decision stands.

## Alternatives considered

- **Vault Transit** would wrap data keys inside Vault. It keeps the KEK out of Studio's memory,
  but every read would depend on Vault, a cache of data keys would be needed, and the provider
  would behave unlike the other three. Rejected for now.
- **A shared data-key table referenced by id.** Rotation would re-wrap a few rows instead of all
  of them, but every read would join, and the spec asks for one data key per secret. Rejected.
- **A key generated and stored in Postgres when none is configured.** It needs zero setup, but a
  database dump would reveal every secret. Rejected.
- **Converting old ciphertext on upgrade.** This is a data migration of the kind the standing
  decision rules out. Rejected.
