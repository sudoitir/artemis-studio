## MODIFIED Requirements

### Requirement: Audit events have a configurable retention, keeping everything by default
Studio SHALL keep audit events forever unless an administrator sets a retention period for the audit
store, which SHALL be at least 30 days. The purge of audit events SHALL itself be recorded as an audit
event that the purge does not remove. Studio SHALL consult every plugin that declares an audit retention
hold before purging, and SHALL leave every record a hold covers, whatever the retention. A hold
provider that errors, does not answer in time or is unavailable SHALL cause that run to purge no audit
event and to report the failure. The purge SHALL NOT remove events a registered receiver has not
acknowledged, up to a configured maximum age. Audit events SHALL be stored in monthly partitions. A
monthly partition SHALL be dropped only when every row in it is eligible: older than the retention,
acknowledged by every reliable receiver or older than the maximum age, and covered by no hold. When a
partition holds any row that is not eligible, the purge SHALL remove only the eligible rows, as it
would without partitions.

#### Scenario: Default
- **WHEN** no audit retention is set
- **THEN** no audit event is purged

#### Scenario: Configured
- **WHEN** a retention is set and events are older than it
- **THEN** the older events are purged, and one `PURGE_STORE` event records the store, the retention and the number purged

#### Scenario: The purge record survives
- **WHEN** the audit store is purged
- **THEN** the `PURGE_STORE` event of that purge is still present afterwards

#### Scenario: Hold respected
- **WHEN** a plugin holds records inside the purge window
- **THEN** the purge leaves them and purges the rest

#### Scenario: Hold provider failure
- **WHEN** a plugin that declares a retention hold errors or does not answer in time
- **THEN** no audit event is purged in that run and the failure is shown in the store's purge status

#### Scenario: A month is wholly eligible
- **WHEN** every row in a monthly partition is older than the retention, acknowledged or past the maximum age, and held by no one
- **THEN** the partition is dropped and the purge is audited

#### Scenario: A month holds one ineligible row
- **WHEN** a monthly partition holds a row that a receiver has not acknowledged, or a hold covers
- **THEN** the partition is kept and only its eligible rows are purged
