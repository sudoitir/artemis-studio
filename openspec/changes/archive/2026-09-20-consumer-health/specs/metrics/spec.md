## ADDED Requirements

### Requirement: In-flight and expired counts are sampled and queryable

The sampled metric set SHALL include the count of messages currently in flight to
consumers (`deliveringCount`, a gauge) and the cumulative count of messages that expired
(`messagesExpired`, a counter), for every queue on every node, on the same schedule as the
existing per-queue metrics. Both SHALL be queryable as series under the existing
aggregation rules for their kind.

#### Scenario: In-flight depth is averaged like a gauge

- **WHEN** a `deliveringCount` series is requested over a range with multiple buckets
- **THEN** each bucket's value is the average observed in that bucket, with its maximum
  reported as a peak

#### Scenario: Expiry is reported as a rate

- **WHEN** a `messagesExpired` series is requested
- **THEN** each bucket's value is the per-second rate derived from the change in the
  counter across that bucket, clamped to zero across a broker restart

#### Scenario: Sampling them costs no additional broker request

- **WHEN** a sweep collects the per-queue metrics for a node
- **THEN** the in-flight and expired counts are taken from the response already retrieved,
  without issuing a further request to the broker
