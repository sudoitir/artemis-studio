## MODIFIED Requirements

### Requirement: Purges run once per installation, in bounded batches, and are audited
Purges SHALL run as an installation-wide job, on exactly one instance at a time. Each store's purge
SHALL delete in batches of bounded size, each in its own transaction, and SHALL write one `PURGE_STORE`
audit event stating the store, the retention and the amount purged. A store kept forever SHALL NOT be
purged. Dropping an expired partition, after detaching it concurrently, SHALL count as a purge and be
audited the same way. Where a purge removes parent rows whose children cascade, it SHALL remove the
children first, in bounded batches, and the parents last.

#### Scenario: Two instances
- **WHEN** two instances share the database and the purge schedule fires on both
- **THEN** the purge runs on one of them, and the other records the run as skipped elsewhere

#### Scenario: Purge under load
- **WHEN** a purge removes a large backlog while the store keeps receiving writes
- **THEN** it deletes in bounded batches, and the concurrent writes succeed without waiting longer than one batch

#### Scenario: A partition is dropped
- **WHEN** a purge drops an expired partition
- **THEN** the partition is detached without blocking writers, then dropped, and one `PURGE_STORE` event states the store, the retention and the rows dropped

#### Scenario: A cascade is purged
- **WHEN** a purge removes runs that have many child rows
- **THEN** the child rows are deleted first in bounded batches and no single statement deletes an unbounded cascade

## ADDED Requirements

### Requirement: High-volume history is time-partitioned and kept ahead
History that grows in proportion to traffic SHALL be stored in time partitions with UTC bounds, and SHALL have no default partition. Studio SHALL create the partitions for the coming period at start-up, before scraping and jobs begin, and again every hour. A write that finds no partition for its time SHALL trigger their creation and be retried once. The health report SHALL show the partitions as DOWN when the current period has none. A partition that has closed SHALL be frozen and analyzed once, and each partitioned parent SHALL be analyzed daily, because the planner does not see statistics of a parent through its partitions.

#### Scenario: Start-up creates partitions
- **WHEN** Studio starts against a database whose newest partition has expired
- **THEN** the partitions for the current and coming periods exist before the first scrape or job runs

#### Scenario: The hourly run keeps ahead
- **WHEN** the hourly run executes
- **THEN** the partitions for the coming period exist and none is created twice

#### Scenario: A write finds no partition
- **WHEN** a write fails because no partition covers its time
- **THEN** Studio creates the partition and retries the write once, and the write succeeds

#### Scenario: The current period has no partition
- **WHEN** the current period has no partition and creation fails
- **THEN** the health report shows the partitions as DOWN and names the table

#### Scenario: A period closes
- **WHEN** a partition's period has ended
- **THEN** the partition is frozen and analyzed once, and is not reprocessed on later runs

#### Scenario: Parents are analyzed
- **WHEN** a day has passed
- **THEN** each partitioned parent has been analyzed
