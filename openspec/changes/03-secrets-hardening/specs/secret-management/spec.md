## ADDED Requirements

### Requirement: Every stored secret is envelope encrypted
Each stored secret SHALL be encrypted with its own data key, and each data key SHALL be wrapped by the current key-encryption key; no secret is stored under the key-encryption key directly or in plain text.

#### Scenario: Broker credential stored
- **WHEN** a broker credential is saved
- **THEN** the database holds only ciphertext and a wrapped data key

#### Scenario: Database dump
- **WHEN** the database is read without the key-encryption key
- **THEN** no secret can be recovered

### Requirement: The key-encryption key can be rotated online
An administrator SHALL be able to start a rotation that re-wraps every data key with a new key-encryption key while Studio keeps serving, and SHALL see its progress and result.

#### Scenario: Rotation in progress
- **WHEN** a rotation runs
- **THEN** reads and writes of secrets keep working and progress is visible

#### Scenario: Rotation interrupted
- **WHEN** Studio stops during a rotation
- **THEN** the rotation resumes and no secret becomes unreadable

### Requirement: Secret providers are selected by configuration
Studio SHALL obtain the key-encryption key from a provider chosen by configuration: environment or file, HashiCorp Vault, or Kubernetes Secrets.

#### Scenario: Provider unreachable at start
- **WHEN** the configured provider cannot deliver the key
- **THEN** Studio refuses to start with a message naming the provider, and no fallback provider is used

#### Scenario: Provider switched
- **WHEN** the configured provider changes
- **THEN** a documented procedure re-wraps the data keys under the new provider's key

### Requirement: Secrets and credential-like values are redacted everywhere they could leak
Studio SHALL redact stored secrets and values that look like credentials in logs, audit parameters, error responses and exported bundles.

#### Scenario: Error response
- **WHEN** a failing call involved a credential
- **THEN** the response body contains no part of it

#### Scenario: Proof by test
- **WHEN** the test suite plants known secrets and exercises logging, audit, errors and export
- **THEN** none of the secrets appears in any output
