## MODIFIED Requirements

### Requirement: Metric samples are written and retained for a bounded window

The system SHALL append queue counter samples to the metric history on the
medium and slow tiers, and SHALL delete history older than a configurable
retention window. The default retention SHALL be seven days. History SHALL be
retained in daily partitions rather than a single unpartitioned table, so that
trimming expired history is a partition drop rather than a row-by-row delete.

#### Scenario: Old samples are trimmed

- **WHEN** a partition's contents are entirely older than the configured
  retention window
- **THEN** that partition is dropped and its samples are no longer queryable

#### Scenario: Samples written before partitioning still age out

- **WHEN** metric samples exist in the fallback partition from before daily
  partitioning began
- **THEN** those samples are still deleted once they exceed the retention
  window, via the existing row-by-row trim on that partition only
