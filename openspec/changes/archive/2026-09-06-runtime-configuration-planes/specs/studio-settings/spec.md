## MODIFIED Requirements

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

## ADDED Requirements

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
