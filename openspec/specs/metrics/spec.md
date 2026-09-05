## Purpose

Defines how an operator queries and views the historical metric samples Studio already
collects — bucketed time-series reads over `metric_sample`, the partition lifecycle that
keeps that table bounded, and the charts that present depth, throughput, consumer count,
and request-reply latency for a cluster or a single queue.

## Requirements

### Requirement: Metric history is queryable as bucketed time series

The system SHALL expose a read endpoint that returns metric samples for a cluster,
optionally scoped to one subject, aggregated into time buckets over a requested range. A
gauge-kind metric SHALL be aggregated by averaging (with the bucket's maximum reported
alongside as a peak); a counter-kind metric SHALL be aggregated as a rate derived from the
change in value across the bucket, never as a raw average of a cumulative counter.

#### Scenario: Depth is averaged, not summed

- **WHEN** a cluster-wide depth series is requested over a range with multiple buckets
- **THEN** each bucket's value is the average `messageCount` observed in that bucket, with
  its maximum reported as a peak

#### Scenario: Throughput is a rate

- **WHEN** a throughput series (`messagesAdded` or `messagesAcked`) is requested
- **THEN** each bucket's value is the per-second rate derived from the change in the
  counter across that bucket, summed across the subjects in scope

#### Scenario: A counter reset never produces a negative rate

- **WHEN** a monotonic counter's value in a later sample is lower than in an earlier one
  within the same bucket (a broker restart)
- **THEN** the computed rate for that bucket is clamped to zero, never negative

#### Scenario: A single queue can be isolated

- **WHEN** a metric series is requested scoped to one queue
- **THEN** only that queue's samples contribute to the returned series

### Requirement: Bucket resolution is server-clamped to a bounded point count

The system SHALL clamp the requested bucket width so that a query's point count stays
within a fixed maximum, and SHALL widen the bucket rather than reject the request when the
requested width would exceed it. The response SHALL report the bucket width actually used
and SHALL indicate when it differs from what was requested.

#### Scenario: A too-fine request is widened

- **WHEN** a query's requested bucket width would return more than the maximum point count
  for the requested range
- **THEN** the system widens the bucket, returns no more than the maximum number of points,
  and marks the response as adjusted

#### Scenario: The bucket floor matches what is actually sampled

- **WHEN** a query requests a bucket width narrower than the fastest tier that samples
  metrics
- **THEN** the system clamps the bucket width up to that tier's interval, since finer
  buckets would not reflect any additional sampled precision

### Requirement: A query range cannot exceed the retention window

The system SHALL reject or clamp a requested range that extends further into the past than
the configured retention window, since no samples exist there.

#### Scenario: Range beyond retention

- **WHEN** a query's `from` is older than the configured retention window
- **THEN** the effective range is clamped to the retention window and the response
  indicates the clamp

### Requirement: Metric history is retained in daily partitions

The system SHALL maintain `metric_sample` as a set of daily partitions, creating partitions
ahead of when they are needed and dropping partitions once their entire contents are older
than the configured retention window. Partition maintenance SHALL NOT block concurrent
inserts of new samples.

#### Scenario: Partitions exist ahead of need

- **WHEN** the partition maintenance job runs
- **THEN** a partition exists for the current day and for enough following days that a
  missed run does not cause a sample write to fail

#### Scenario: Expired partitions are dropped, not scanned

- **WHEN** a partition's entire date range is older than the retention window
- **THEN** the partition is detached and dropped as a whole, without a row-by-row delete

#### Scenario: Dropping old partitions does not stall writes

- **WHEN** an old partition is being dropped
- **THEN** concurrent inserts of new metric samples continue to succeed without waiting on
  the drop

### Requirement: Charts disclose sampling gaps and coverage limits

The system SHALL render a gap in the underlying samples as a gap in the chart, not as an
interpolated line, and SHALL disclose when a shown metric has no persisted history (for
example, request-reply latency, which is a live window only).

#### Scenario: A cold subject shows a gap, not a flat line

- **WHEN** a subject was not sampled during part of the requested range
- **THEN** the chart shows a visible gap for that part of the range rather than connecting
  across it

#### Scenario: Latency is labelled as a live window

- **WHEN** the request-reply latency chart is shown
- **THEN** it is captioned as reflecting only the current live window, not persisted
  history

### Requirement: The latest per-subject rate is queryable in one batched read

The system SHALL expose a read that returns, for a given cluster and rate-kind metric,
the current computed rate for every subject with enough recent samples to compute one,
in a single query rather than one query per subject. The computation SHALL use the same
restart-safe never-negative rate derivation as bucketed history reads. A subject with
fewer than two samples in the lookback window SHALL be omitted from the result rather
than reported as zero.

#### Scenario: One query serves every subject

- **WHEN** the current throughput rate is needed for every queue in a cluster
- **THEN** a single read returns the rate for every queue with enough samples, without
  a separate query per queue

#### Scenario: An under-sampled subject is omitted, not zero

- **WHEN** a subject has fewer than two samples within the lookback window
- **THEN** it is absent from the result rather than reported with a rate of zero

#### Scenario: A counter reset never produces a negative rate

- **WHEN** a subject's counter value resets within the lookback window
- **THEN** its computed rate for that window is never negative
