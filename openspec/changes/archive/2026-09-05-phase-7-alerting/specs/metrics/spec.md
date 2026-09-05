## ADDED Requirements

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
