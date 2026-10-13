## MODIFIED Requirements

### Requirement: Metric samples are written and retained for a bounded window

The system SHALL append queue counter samples to the metric history on the
medium and slow tiers, and SHALL delete history older than a configurable
retention window. The default retention SHALL be seven days. History SHALL be
retained in daily partitions with UTC bounds and no default partition, so that
trimming expired history is a partition drop rather than a row-by-row delete.
A sample for a queue, node and time that is already stored SHALL be ignored,
not stored twice, so that a repeated scrape does not duplicate it.

#### Scenario: Old samples are trimmed

- **WHEN** a partition's contents are entirely older than the configured
  retention window
- **THEN** that partition is dropped and its samples are no longer queryable

#### Scenario: A sample is scraped twice

- **WHEN** the same sample is written a second time, for instance after a retry
- **THEN** the second write is ignored and the history holds one row

#### Scenario: A sample has no partition

- **WHEN** a sample's day has no partition
- **THEN** the partition is created and the write retried once, as for any partitioned history

#### Scenario: Samples written before partitioning still age out

- **WHEN** samples sit in a partition that is only partly older than the
  retention window
- **THEN** they remain until the whole partition is older than the window, and
  are then dropped with it
