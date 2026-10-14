## MODIFIED Requirements

### Requirement: The index is retention-bounded and self-maintaining

Every indexed message SHALL be removed once it is older than its subscription's retention
period. Retention SHALL default to a short period, and the default SHALL be short enough
that an unattended subscription does not accumulate payload indefinitely.

A subscription SHALL additionally be bounded by the amount it may hold. Retention expressed
only in time was sized for a sample; a complete capture of a busy address can exceed any
reasonable storage budget long before the retention period elapses. On reaching its size
bound a subscription SHALL stop growing and SHALL be reported as degraded, with the cause
named — it SHALL NOT silently discard the bound or silently stop recording.

Storage SHALL be reclaimed by removing whole time ranges rather than by deleting rows one at
a time, and the maintenance that does so SHALL run without operator action. The index SHALL be
stored in daily partitions with UTC bounds and no default partition, created and dropped by the
same partition maintenance as other high-volume history, and a partition SHALL be detached
concurrently before it is dropped.

Retention maintenance SHALL be governed by every subscription that exists, **including one
that is currently disabled**. Disabling a subscription pauses recording; it SHALL NOT cause
what that subscription already captured to be destroyed sooner than its retention period.

The system SHALL report, per subscription, how much the index currently holds against both
bounds, so that the cost of a subscription is visible to the operator who created it.

#### Scenario: Expired messages are removed

- **WHEN** an indexed message ages past its subscription's retention period
- **THEN** it is removed from the index without operator action

#### Scenario: Disabling a subscription does not destroy what it captured

- **WHEN** a subscription is disabled and its retention period has not elapsed
- **THEN** the messages it already captured remain queryable until that period elapses

#### Scenario: A subscription reaching its size bound is reported

- **WHEN** a subscription reaches the amount it may hold
- **THEN** it is reported as degraded with the cause named, rather than silently continuing to grow or silently ceasing to record

#### Scenario: A subscription's footprint is visible

- **WHEN** an operator views an index subscription
- **THEN** the number of messages held and the storage used are shown against both its retention period and its size bound

#### Scenario: A day of the index is dropped

- **WHEN** a daily partition holds only messages past every subscription's retention
- **THEN** it is detached concurrently and dropped, with no writer blocked
