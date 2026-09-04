# studio-settings Specification

## Purpose
Defines the operational settings an operator can change from Artemis Studio
without restarting it — scrape cadences, the per-node management-call ceiling,
and metric retention — and the rotation of stored broker credentials.

## Requirements

### Requirement: Operational settings are stored and overridable at runtime

The system SHALL persist a set of operational settings — the three scrape tier
intervals, the per-node management-call ceiling, the metric retention window, and
the bulk-operation safety cap — and SHALL use the stored value when present,
falling back to the packaged configuration default otherwise. A changed setting
SHALL take effect without a restart, by the next relevant scheduled run or the
next request that reads it. The bulk safety cap SHALL default to 1000.

#### Scenario: Default until overridden

- **WHEN** no stored value exists for a setting
- **THEN** the system uses the packaged configuration default and reports that
  value as the current setting

#### Scenario: Change takes effect on the next run

- **WHEN** an operator changes the metric retention window
- **THEN** the next retention run trims to the new window without a restart

#### Scenario: Bulk cap change takes effect on the next mutation

- **WHEN** an operator lowers the bulk safety cap
- **THEN** the next bulk mutation is evaluated against the new cap without a
  restart

#### Scenario: Invalid value is rejected

- **WHEN** an operator submits a non-positive interval, ceiling, or cap
- **THEN** the update is rejected with a validation message and the previous
  value is kept

### Requirement: Settings are readable and writable through the API

The system SHALL expose reading the current settings and updating them. Reading
SHALL return every setting with its effective value and whether it is a stored
override or a default.

#### Scenario: Read reports source

- **WHEN** the settings are read after one has been overridden
- **THEN** the overridden setting is marked as a stored override and the rest as
  defaults

### Requirement: Broker credentials can be rotated

The system SHALL allow replacing a cluster's stored broker credentials. The new
credentials SHALL be stored only as authenticated ciphertext bound to that
cluster, the change SHALL be audited in the same transaction as the write, and
no response SHALL ever contain the credentials in plaintext.

#### Scenario: Rotation re-encrypts and audits

- **WHEN** an operator submits new broker credentials for a cluster
- **THEN** the stored ciphertext is replaced, an audit event records the
  rotation and its outcome, and the response contains no secret

#### Scenario: Next scrape uses the new credentials

- **WHEN** credentials are rotated and the next scrape runs
- **THEN** the scrape authenticates with the new credentials

#### Scenario: Rotation is guarded in the UI

- **WHEN** an operator rotates credentials from the frontend
- **THEN** the UI requires the cluster name to be typed to confirm
