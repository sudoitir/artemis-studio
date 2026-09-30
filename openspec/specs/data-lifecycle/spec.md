# data-lifecycle Specification

## Purpose
Every store that grows with use, core or plugin, has one bounded retention policy, a quota and a previewable, audited, batched purge that runs once per installation, and the tables behind them are watched for health (ADR-0132).

## Requirements

### Requirement: Every store has one retention policy in one place
Studio SHALL expose a retention policy for every store that grows with use: metrics, message index,
captured payloads, broker events, request-reply flows, audit, bulk runs, transfer runs, alert history,
broker configuration history, setup reviews, classification findings, expired sessions and storage
samples. Each policy SHALL have a default and an enforced minimum and maximum, and SHALL be editable on
the Data page by a user holding `data:write`. A change SHALL be audited and SHALL apply at the next
purge, without a restart. These policies SHALL NOT also be listed on the Settings page.

#### Scenario: Set a policy
- **WHEN** an administrator sets a store's retention within its bounds
- **THEN** the policy is saved, an `UPDATE_SETTING` audit event records the old and new values, and the next purge uses it

#### Scenario: Out of bounds
- **WHEN** a retention outside the store's bounds is submitted
- **THEN** it is rejected with a message naming the allowed range, and nothing is saved or audited

#### Scenario: Forever only where allowed
- **WHEN** `forever` is submitted for a store whose bounds do not allow it
- **THEN** it is rejected with the allowed range

#### Scenario: Writing needs the data permission
- **WHEN** a user without `data:write` changes a policy
- **THEN** the request is refused with 403

#### Scenario: No unbounded store
- **WHEN** the tables created by the changelogs are compared with the tables the stores name
- **THEN** every table either belongs to a store or is listed as bounded by design with a reason, and a build test fails for a new table that is neither

#### Scenario: Message-index subscriptions are capped by the store
- **WHEN** a subscription's retention is set longer than the message-index store's retention
- **THEN** it is rejected with the store's retention as the maximum

### Requirement: A policy change can be previewed before it applies
Studio SHALL offer a dry run that reports, for one store and a proposed retention, how many rows (or
partitions) and about how many bytes a purge would remove, without deleting anything.

#### Scenario: Dry run
- **WHEN** an administrator previews a shorter retention for a store
- **THEN** the rows and estimated bytes are shown, and every row is still present afterwards

### Requirement: Stores have quotas with warning thresholds
Each store SHALL support an optional quota, in bytes or rows depending on the store, and a warning
percentage (default 80). A store whose usage crosses the warning SHALL raise an installation alert.

#### Scenario: Threshold crossed
- **WHEN** a store's usage passes its warning percentage of its quota
- **THEN** a `STORAGE_QUOTA` installation alert fires naming the store, its usage and its quota

#### Scenario: No quota
- **WHEN** a store's quota is 0
- **THEN** it never raises a quota alert

### Requirement: Storage health is observable
Studio SHALL show, per table, its size, its growth over the last 7 days, its dead tuples, its last
vacuum and, for partitioned tables, whether partitions exist for today and the next three days. It SHALL
mark unhealthy tables and raise a `STORAGE_HEALTH` installation alert for them.

#### Scenario: Missing partition
- **WHEN** a partitioned store lacks the partition for an upcoming day
- **THEN** the health page marks the gap and a `STORAGE_HEALTH` alert names the table

#### Scenario: Bloated table
- **WHEN** more than 20% of a table's tuples, and more than 10,000, are dead
- **THEN** the table is marked unhealthy with its dead-tuple share

### Requirement: Plugins register their stores through a housekeeping contract
The plugin API SHALL let a plugin contribute stores through `HousekeepingContributor`, each with its
retention bounds, usage, dry run and batched purge. Studio SHALL list, preview, purge and report a
plugin store like a core store, and SHALL remove it when the plugin is uninstalled.

#### Scenario: Plugin store listed
- **WHEN** a plugin contributes a store
- **THEN** it appears on the Data page under the plugin's id, with its policy and usage

#### Scenario: Failing contributor
- **WHEN** a store throws during a purge
- **THEN** the other stores still purge, and the failing store's audit event and last-purge status carry the error

### Requirement: Purges run once per installation, in bounded batches, and are audited
Purges SHALL run as an installation-wide job, on exactly one instance at a time. Each store's purge
SHALL delete in batches of bounded size, each in its own transaction, and SHALL write one `PURGE_STORE`
audit event stating the store, the retention and the amount purged. A store kept forever SHALL NOT be
purged.

#### Scenario: Two instances
- **WHEN** two instances share the database and the purge schedule fires on both
- **THEN** the purge runs on one of them, and the other records the run as skipped elsewhere

#### Scenario: Purge under load
- **WHEN** a purge removes a large backlog while the store keeps receiving writes
- **THEN** it deletes in bounded batches, and the concurrent writes succeed without waiting longer than one batch
