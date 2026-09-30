# secret-management Specification

## Purpose
How Studio protects every secret it stores: envelope encryption under versioned key-encryption keys from a configured key provider, online rotation of those keys, and redaction of credentials wherever output could leak them (ADR-0132, ADR-0133).

## Requirements

### Requirement: Every stored secret is envelope encrypted
Each stored secret SHALL be encrypted with its own data key, and each data key SHALL be wrapped by a key-encryption key identified by version. This covers broker credentials, bridge credentials, notification channel secrets, plugin vault entries and governance-sealed message originals. No secret is stored under the key-encryption key directly or in plain text. The ciphertext SHALL stay bound to its row through additional authenticated data (ADR-0009).

#### Scenario: Broker credential stored
- **WHEN** a broker credential is saved
- **THEN** the database holds only one sealed value carrying the key version, a wrapped data key and the ciphertext

#### Scenario: Database dump
- **WHEN** the database is read without the key-encryption key
- **THEN** no secret can be recovered

#### Scenario: A ciphertext moved to another row
- **WHEN** a stored sealed value is copied into another cluster's or another kind's row
- **THEN** decryption fails there

#### Scenario: Stored secrets from an earlier version
- **WHEN** Studio is upgraded to this version
- **THEN** secrets stored in the earlier format are removed and must be entered again, and the release note says so

### Requirement: The key-encryption key can be rotated online
An administrator SHALL be able to start a rotation to the newest key version the provider offers. The rotation SHALL re-wrap every data key with that version while Studio keeps serving, and the administrator SHALL see its progress and result. New secrets SHALL be wrapped with the new version from the moment the rotation starts.

#### Scenario: Rotation in progress
- **WHEN** a rotation runs
- **THEN** reads and writes of secrets keep working and progress is visible

#### Scenario: Rotation interrupted
- **WHEN** Studio stops during a rotation
- **THEN** the rotation resumes after the restart and no secret becomes unreadable

#### Scenario: Rotation with several replicas
- **WHEN** two replicas serve while a rotation runs
- **THEN** both read every secret throughout, the rotation runs on one of them only, and it ends only when no secret remains under an older version

#### Scenario: No newer key
- **WHEN** a rotation is started and the provider offers no version newer than the current one
- **THEN** it is refused with a conflict that says a newer key must be added first

#### Scenario: Rotation without permission
- **WHEN** a caller without the settings-write permission or without fresh authentication starts a rotation
- **THEN** it is refused and audited

### Requirement: The key provider is optional and selected by configuration
Studio SHALL obtain its key-encryption keys and the OIDC client secret from one provider chosen by configuration: environment (the default when none is configured), file, HashiCorp Vault (KV version 2) or Kubernetes Secrets. The environment provider SHALL accept either a single base64 key, which is version 1, or a list of numbered keys.

#### Scenario: No provider configured
- **WHEN** no provider is configured and `ARTEMIS_STUDIO_SECRET_KEY` holds a 32-byte base64 key
- **THEN** Studio starts, encrypts secrets at rest in its database under key version 1, and reports the provider as environment

#### Scenario: Provider unreachable at start
- **WHEN** the configured provider cannot deliver a valid key
- **THEN** Studio refuses to start with a message naming the provider, and no fallback provider is used

#### Scenario: Wrong-length key
- **WHEN** a key from the provider does not decode to exactly 32 bytes
- **THEN** startup fails with an error naming the provider and the key version, stating that a 32-byte key is required

#### Scenario: OIDC client secret from the provider
- **WHEN** OIDC is enabled and the provider holds the OIDC client secret
- **THEN** Studio authenticates to the identity provider with it

#### Scenario: Provider switched
- **WHEN** the configured provider changes
- **THEN** a documented procedure moves the current key to the new provider and re-wraps the data keys under a key held there

### Requirement: Secrets and credential-like values are redacted everywhere they could leak
Studio SHALL redact stored secrets and values that look like credentials in logs, audit parameters, error responses and exported configuration.

#### Scenario: Error response
- **WHEN** a failing call involved a credential
- **THEN** the response body contains no part of it

#### Scenario: Log line
- **WHEN** a log message or stack trace contains a password assignment, a bearer token or a URL with user information
- **THEN** the written line shows a redaction marker in place of the value

#### Scenario: Audit parameters
- **WHEN** an audited action carries a parameter whose name is credential-like
- **THEN** the stored audit row holds a redaction marker in place of its value

#### Scenario: Proof by test
- **WHEN** the test suite plants known secrets and exercises logging, audit, errors and export
- **THEN** none of the secrets appears in any output
