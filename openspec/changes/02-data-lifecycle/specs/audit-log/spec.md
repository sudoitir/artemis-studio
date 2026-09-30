## ADDED Requirements

### Requirement: Audit events have a configurable retention, keeping everything by default
Studio SHALL keep audit events forever unless an administrator sets a retention period for the audit
store, which SHALL be at least 30 days. The purge of audit events SHALL itself be recorded as an audit
event that the purge does not remove.

#### Scenario: Default
- **WHEN** no audit retention is set
- **THEN** no audit event is purged

#### Scenario: Configured
- **WHEN** a retention is set and events are older than it
- **THEN** the older events are purged, and one `PURGE_STORE` event records the store, the retention and the number purged

#### Scenario: The purge record survives
- **WHEN** the audit store is purged
- **THEN** the `PURGE_STORE` event of that purge is still present afterwards
