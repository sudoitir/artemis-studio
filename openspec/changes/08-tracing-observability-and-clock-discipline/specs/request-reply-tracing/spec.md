## ADDED Requirements

### Requirement: Tracing states why it is producing no flows

The system SHALL report, per traced request address, what its sampler did on its
most recent attempt: when it last attempted, when it last succeeded, how many
nodes it sampled, which nodes it did not sample **and the reason for each**, how
many messages it read, how many observations it produced, and its last error.

When no flows are available to show, the system SHALL present that report
together with the possible reasons, ordered most likely first, each stated with
the action that addresses it. An empty flow list SHALL NOT be presented without
that explanation.

#### Scenario: An empty flow list is explained

- **WHEN** an operator opens the flows view for a cluster with a declared address
  and no reconstructed flows
- **THEN** the view states what the sampler did and the ranked reasons, each with
  its remedy, rather than only a count of zero

#### Scenario: The sampling ceiling is always disclosed

- **WHEN** the reasons are presented
- **THEN** they include that a request consumed faster than the sampling interval
  is never observed, and what to change if that matters

#### Scenario: A skipped node names its reason

- **WHEN** a node is not sampled for a traced address
- **THEN** the report names that node and why it was not sampled

### Requirement: A latency figure states how it was measured

Every reconstructed flow SHALL record whether its latency was derived from
observation — the interval between the system's own samples — or from the
messages' own timestamps, and SHALL expose that alongside the figure.

An observed latency SHALL carry the bound implied by the sampling interval, and
the system SHALL present that bound wherever it presents the figure.

The system SHALL prefer the messages' own timestamps only when both were observed
with trustworthy clocks and the result is non-negative. A negative result SHALL
NOT be stored as a latency: it SHALL fall back to observation and record the clock
disagreement.

#### Scenario: Two messages seen on one sample are still measured

- **WHEN** a request and its reply are both read on the same sampling pass and
  both carry trustworthy timestamps
- **THEN** the latency reported is the difference between those timestamps, marked
  as coming from message timestamps, rather than approximately zero

#### Scenario: An observed latency shows its error bar

- **WHEN** a latency was derived from observation
- **THEN** it is presented with the sampling interval as its bound

#### Scenario: A reply that predates its request is refused

- **WHEN** the reply's timestamp is earlier than the request's
- **THEN** no negative latency is stored, the flow falls back to the observed
  figure, and the clock disagreement is recorded against the flow

### Requirement: Producer and consumer clock skew is recorded, forward only

The system SHALL compare a message's own timestamp, normalised onto its own
timeline, against the moment it observed that message, and SHALL record the
difference as skew only when the message claims to have been produced **after**
the observation.

A timestamp earlier than the observation SHALL NOT be recorded as skew, because it
is indistinguishable from a message that waited on the queue.

#### Scenario: A message from the future is recorded as skew

- **WHEN** a request's timestamp is further ahead of the observation than the
  configured tolerance
- **THEN** the flow records the skew and an event names which side's clock it was

#### Scenario: Queue residency is not skew

- **WHEN** a message's timestamp precedes the observation
- **THEN** nothing is recorded as skew

## MODIFIED Requirements

### Requirement: Sampling honours the configured rate and the queue that exists

The system SHALL sample a traced address no more often than the per-expectation
rate the operator configured, and SHALL report when that rate is faster than the
global sampling interval can deliver.

The system SHALL browse the queue the broker actually reports for an address,
using that queue's name and routing type rather than assuming either. An address
with no known queue SHALL be reported as a stated reason, not repeatedly retried
as an error.

#### Scenario: A configured rate is applied

- **WHEN** an expectation is configured to be sampled less often than the global
  interval
- **THEN** it is sampled at its own rate rather than on every tick

#### Scenario: A rate faster than the floor is disclosed

- **WHEN** an expectation asks to be sampled more often than the global sampling
  interval permits
- **THEN** the diagnostics state that the global interval is the floor

#### Scenario: A multicast address is browsed correctly

- **WHEN** a traced address is served by a queue whose name differs from the
  address, or whose routing type is not anycast
- **THEN** that queue is browsed by its own name and routing type

#### Scenario: No Core endpoint is named as the reason

- **WHEN** no node in the cluster has a reachable Core endpoint
- **THEN** tracing reports that as the reason it produced nothing, rather than
  producing nothing silently
