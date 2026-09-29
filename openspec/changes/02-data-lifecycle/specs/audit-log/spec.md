## ADDED Requirements

### Requirement: Audit events have a configurable retention, keeping everything by default
Studio SHALL keep audit events forever unless an administrator sets a retention period, and SHALL record the purge of audit events itself as an audit event.

#### Scenario: Default
- **WHEN** no audit retention is set
- **THEN** no audit event is purged

#### Scenario: Configured
- **WHEN** a retention is set and events exceed it
- **THEN** older events are purged and one event records the purge
