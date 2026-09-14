# studio-settings Specification

## Purpose
Defines how Artemis Studio is configured: the operational settings an operator
can change from the application without restarting it and the audit trail of
those changes, the deploy-time properties a deployment supplies from its own
database before the application starts, and the rotation of stored broker
credentials.

## Requirements

### Requirement: Operational settings are stored and overridable at runtime

The system SHALL maintain a registry of operational settings, each declaring its
grouping, human label, description, value kind, packaged default, and how a change
is applied. The registry SHALL be the single definition of the tunable set: adding
a setting SHALL require no separate change to the API, the validation rules, or the
settings screen.

The registry SHALL cover, at minimum: the three scrape tier intervals; the per-node
management-call ceiling; the broker connect and read timeouts; the bulk-operation
safety cap; the metric, event-history and request-reply retention windows and the
schedule each retention run uses; the event write-buffer size and flush interval;
the request-reply default deadline, payload capture cap, deadline-sweep interval
and sampler interval; the notification dispatch interval, maximum delivery attempts
and retry backoff bounds; and the realtime-stream keep-alive interval.

The system SHALL use a stored value when present and the packaged configuration
default otherwise. **The system SHALL NOT write stored values for settings that
have not been changed**, so that a deployment which has never overridden a setting
adopts an improved default on upgrade rather than remaining pinned to the default
current when it first started.

Every setting SHALL take effect without a restart — by the next read for a setting
read per use, by the next scheduled fire for a setting that governs a schedule, and
immediately for a setting cached by the component that uses it. The bulk safety cap
SHALL default to 1000 and the event-history retention window SHALL default to 72
hours.

#### Scenario: Default until overridden

- **WHEN** no stored value exists for a setting
- **THEN** the system uses the packaged configuration default and reports that
  value as the current setting

#### Scenario: No stored value is created before an operator changes one

- **WHEN** the system starts against a database in which no setting has been changed
- **THEN** no stored setting values are created, and a later upgrade that changes a
  packaged default takes effect

#### Scenario: Change takes effect on the next run

- **WHEN** an operator changes the metric retention window
- **THEN** the next retention run trims to the new window without a restart

#### Scenario: Bulk cap change takes effect on the next mutation

- **WHEN** an operator lowers the bulk safety cap
- **THEN** the next bulk mutation is evaluated against the new cap without a
  restart

#### Scenario: Event retention change takes effect on the next reap

- **WHEN** an operator changes the event-history retention window
- **THEN** the next event reap trims to the new window without a restart

#### Scenario: A changed cadence is honoured on the next fire

- **WHEN** an operator changes the interval or schedule of a recurring task
- **THEN** the task's next run is computed from the new value without a restart,
  and the value the task uses is never allowed to differ from the value reported by
  the settings API

#### Scenario: A changed broker timeout applies to the next broker call

- **WHEN** an operator changes the broker read timeout
- **THEN** the next call to a broker uses the new timeout without a restart

#### Scenario: Invalid value is rejected

- **WHEN** an operator submits a non-positive interval, ceiling, cap, window, or
  buffer size
- **THEN** the update is rejected with a validation message and the previous
  value is kept

#### Scenario: A schedule that would run too often is rejected

- **WHEN** an operator submits a schedule expression that is malformed, or that
  would run a retention task more than once a minute
- **THEN** the update is rejected and the previous schedule remains in effect

### Requirement: Settings are readable and writable through the API

The system SHALL expose reading the current settings and updating them. Reading
SHALL return every setting with its effective value, whether it is a stored
override or a default, and enough description — grouping, label, explanation and
value kind — for a client to present an editor for a setting it has no built-in
knowledge of.

#### Scenario: Read reports source

- **WHEN** the settings are read after one has been overridden
- **THEN** the overridden setting is marked as a stored override and the rest as
  defaults

#### Scenario: A client renders a setting it does not know

- **WHEN** a setting the client has no built-in knowledge of is returned
- **THEN** the client can present it, under its grouping and with its label and
  explanation, without a client-side change

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

### Requirement: Settings writes require a global write permission

Reading operational settings SHALL require an authenticated principal.
Updating a setting, including the bulk-operation safety cap, SHALL require a
global settings-write permission.

#### Scenario: Settings write requires global permission

- **WHEN** a user without global settings-write permission attempts to change
  a setting
- **THEN** the request is rejected

#### Scenario: The bulk safety cap cannot be raised without permission

- **WHEN** a user without global settings-write permission attempts to raise
  the bulk-operation safety cap
- **THEN** the request is rejected and the previous cap remains in effect

### Requirement: Settings changes are audited

The system SHALL record an audit event for every change to an operational setting
and for every reset of one, in the same transaction as the change, capturing the
setting, the previous value and the new value. A submission rejected by validation
SHALL NOT produce an audit event, since no change was attempted.

#### Scenario: A change is recorded with what it replaced

- **WHEN** an operator changes a setting
- **THEN** an audit event records the setting, its previous value, its new value
  and the outcome, and commits or rolls back with the change

#### Scenario: A reset is recorded

- **WHEN** an operator clears an override
- **THEN** an audit event records the reset back to the packaged default

#### Scenario: A rejected value leaves no trace

- **WHEN** an operator submits a value that fails validation
- **THEN** no audit event is written and no stored value changes

### Requirement: Deploy-time properties can be supplied from the database

The system SHALL support supplying deploy-time configuration — the values read
while the application is starting, which therefore cannot be changed from within
it — from a database table keyed by application, profile and label, applied before
the application's own components are created. A value MAY be stored encrypted and
SHALL be decrypted at startup using a key supplied to the deployment.

The key used for deploy-time property values SHALL be distinct from the key that
seals stored broker credentials.

The system SHALL start normally when this configuration source is unavailable —
including when the table does not yet exist, because schema migration has not yet
run — treating it as contributing no properties rather than as a failure.

#### Scenario: A stored property is applied at startup

- **WHEN** a property is stored for the running application and profile, and the
  application starts
- **THEN** the application uses the stored value in place of its packaged default

#### Scenario: A profile-specific value wins

- **WHEN** a property is stored both for the shared profile and for an active
  profile
- **THEN** the active profile's value is the one applied

#### Scenario: A first start with no schema still succeeds

- **WHEN** the application starts against a database whose schema has not yet been
  migrated
- **THEN** startup proceeds using packaged defaults, and the unavailable
  configuration source is reported as a warning rather than an error

#### Scenario: An encrypted value is readable

- **WHEN** a property is stored in encrypted form and the decryption key is supplied
- **THEN** the application uses the decrypted value

### Requirement: Re-reading configuration requires the settings-write permission

The system SHALL expose an operation that re-reads deploy-time configuration into
the running application, and SHALL require the same global settings-write
permission as changing an operational setting.

#### Scenario: Re-reading configuration is not public

- **WHEN** an unauthenticated caller invokes the configuration re-read operation
- **THEN** the request is rejected

### Requirement: A disabled feature's settings are neither listed nor writable

The operational settings registry SHALL contain only settings declared by enabled modules. A setting belonging to a disabled feature SHALL NOT appear in the settings read or on the settings screen. A write or reset of such a setting SHALL be refused with a `404` problem detail stating the owning feature is disabled. A value already stored for that setting SHALL be kept, not deleted, and SHALL apply again if the feature is re-enabled.

#### Scenario: Disabled feature's settings are not listed

- **WHEN** the settings are read on an installation with a feature disabled
- **THEN** none of that feature's settings appear

#### Scenario: Writing a disabled feature's setting is refused

- **WHEN** a caller with the settings-write permission writes a setting that belongs to a disabled feature
- **THEN** the request is refused with `404`, the problem detail names the disabled feature, and no audit event records a change

#### Scenario: A stored override survives disablement

- **WHEN** a feature with an overridden setting is disabled and later re-enabled
- **THEN** the previously stored value is in effect again
