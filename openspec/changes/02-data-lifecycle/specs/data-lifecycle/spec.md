## ADDED Requirements

### Requirement: Every store has one retention policy in one place
Studio SHALL expose a retention policy for each of metrics, broker events, request-reply flows, captured payloads, the message index and audit, each with a default and enforced minimum and maximum, editable from one settings page.

#### Scenario: Set a policy
- **WHEN** an administrator sets retention within bounds
- **THEN** the policy is saved, audited and applied at the next purge

#### Scenario: Out of bounds
- **WHEN** a value outside the bounds is submitted
- **THEN** it is rejected with the allowed range

### Requirement: A policy change can be previewed before it applies
Studio SHALL offer a dry run that reports, per store, how many rows or partitions and how much space a proposed policy would purge, without deleting anything.

#### Scenario: Dry run
- **WHEN** an administrator previews a shorter retention
- **THEN** the counts and sizes are shown and no data is removed

### Requirement: Stores have quotas with warning thresholds
Each store SHALL support a size or row quota and a warning threshold; crossing the threshold SHALL raise an alert.

#### Scenario: Threshold crossed
- **WHEN** a store passes its warning threshold
- **THEN** an alert is raised naming the store and its usage

### Requirement: Storage health is observable
Studio SHALL show, per table, its size, growth, bloat or dead tuples, last vacuum and, for partitioned tables, partition coverage, and SHALL alert on unhealthy values.

#### Scenario: Missing partition
- **WHEN** a partitioned store lacks the partition for an upcoming period
- **THEN** the health page and an alert report the gap

### Requirement: Plugins register their stores through a housekeeping contract
The plugin API SHALL let a plugin contribute a store with its retention rule, a dry run and metrics, and Studio SHALL run, preview and report it like a core store.

#### Scenario: Plugin store listed
- **WHEN** a plugin contributes a store
- **THEN** it appears on the retention page with its policy and usage

#### Scenario: Failing contributor
- **WHEN** a contributor throws during a purge
- **THEN** the other stores still purge and the failure is reported

### Requirement: Purges run once per installation and are audited
A purge SHALL run on exactly one instance at a time and SHALL write an audit event stating the store, the policy and the amount purged.

#### Scenario: Two instances
- **WHEN** two instances share the database
- **THEN** each scheduled purge runs once
