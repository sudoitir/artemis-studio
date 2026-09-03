# ADR-0009: Secret vaulting with JDK AES-GCM; broker TLS via SSL bundles

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

## Context

Phase 1 is the first release that stores something worth protecting: broker
credentials (`broker_credential.secret_ct` / `secret_nonce`, already in
`002-estate.sql`) and, for TLS brokers, trust material. The schema comment
already says "AES-GCM ciphertext; key from env/file, never stored" and
`architecture.md` names "credentials (AES-GCM)", but no ADR fixes the mechanism,
the key handling, or how a client trusts an HTTPS broker.

Constraints: the global rules say prefer an established library only when it
*reduces* complexity, and reach for the platform before a dependency. The JDK
ships authenticated encryption and Spring Boot ships an SSL abstraction; both
are already on the classpath.

## Decision

- **Encryption is `AES/GCM/NoPadding` from the JDK.** A `SecretVault` component
  wraps `javax.crypto.Cipher`. Per secret: a fresh 96-bit nonce from
  `SecureRandom`, a 128-bit authentication tag. No crypto library is added — a
  wrapper around one `Cipher` call each way is less code and less supply-chain
  surface than pulling one in.
- **One root key, from the environment.** `ARTEMIS_STUDIO_SECRET_KEY` is read at
  startup and must base64-decode to **exactly 32 bytes** (AES-256). Missing or
  wrong-length ⇒ the bean fails to construct and the application does not start.
  The key is never written to the database, a log line, or an error message.
- **Additional authenticated data binds every ciphertext to its row.** AAD is
  `clusterId + "|" + kind`. A ciphertext lifted into another cluster's or
  another kind's row fails authentication on decrypt rather than leaking.
- **Broker TLS uses Spring Boot SSL bundles.** `broker_tls.truststore_ref`
  holds a **bundle name**, resolved through the auto-configured `SslBundles`
  bean and applied to the per-cluster `RestClient`'s request factory.
  `broker_tls.verify_hostname` maps to the bundle's options. Studio does not ask
  an operator for a truststore path plus password — the schema has no password
  column and the framework already solves key/trust material loading.

## Consequences

- Zero new dependencies for credential security.
- A lost or rotated `ARTEMIS_STUDIO_SECRET_KEY` makes existing ciphertext
  unrecoverable — acceptable, and standard for envelope-free at-rest encryption;
  re-entry of credentials is the recovery path. There is no stored ciphertext in
  any environment yet, so the dev key can change value once with no migration.
- No key rotation mechanism. When one is needed it is a new ADR (decрypt-all,
  re-encrypt under a new key, versioned nonce column) — not built speculatively.
- SSL bundles must be configured in `application.yml` / env for each TLS broker;
  a missing bundle is a clear startup-independent connection error, not a silent
  fallback to no verification.

## Alternatives considered

- **A dedicated crypto library (Tink, Bouncy Castle).** More primitives than a
  single-key at-rest use needs; adds a dependency to audit. Rejected.
- **Spring Security `Encryptors.stronger()`.** Uses AES-GCM under the hood, but
  its salt/iteration model is aimed at password-derived keys and it offers no
  place to bind AAD to the row. A direct `Cipher` call is clearer here.
- **Truststore path + password columns.** Would mean a schema change and
  re-implementing what `SslBundles` already does. Rejected.
- **An external secret manager (Vault, cloud KMS).** Right answer at scale, wrong
  weight for a single-container compose-first product. Revisit if a deployment
  needs it.
