## Purpose

Answers why a queue's backlog is not draining, by classifying every queue into one
ordered verdict backed by the evidence behind it, so an operator can rank a cluster's
queues worst-first and act on the cause rather than the symptom.

## ADDED Requirements

### Requirement: A queue's consumer health is one verdict from an ordered ladder

The system SHALL classify each queue into exactly one verdict, evaluated as an ordered
ladder where the first matching condition wins: `INSUFFICIENT_DATA`, `PAUSED`,
`NO_CONSUMERS`, `BROKER_SLOW`, `STALLED`, `STARVED`, `FALLING_BEHIND`, `DRAINING`,
`HEALTHY`. The ladder SHALL be fixed and identical for every caller; it SHALL NOT be
operator-configurable.

#### Scenario: A queue with a backlog and no consumers

- **WHEN** a queue has a depth above zero and no consumers attached
- **THEN** its verdict is `NO_CONSUMERS`

#### Scenario: A paused queue is not reported as a consumer fault

- **WHEN** a queue is paused on any node and has a backlog
- **THEN** its verdict is `PAUSED`, ahead of any consumer-related verdict

#### Scenario: A draining queue states a recovery, not a fault

- **WHEN** a queue's acknowledgement rate exceeds its enqueue rate and its depth is above
  zero
- **THEN** its verdict is `DRAINING` and an estimated time to drain is reported

#### Scenario: One verdict only

- **WHEN** a queue satisfies the conditions of more than one verdict
- **THEN** only the highest-priority matching verdict is reported

### Requirement: A stalled consumer is distinguished from a starved one

The system SHALL distinguish a queue whose consumers hold messages without acknowledging
them from a queue whose consumers are attached but receive nothing, using the count of
messages currently in flight. These two conditions lead to opposite operator actions and
MUST NOT be reported under one verdict.

#### Scenario: Consumers hold messages and acknowledge none

- **WHEN** a queue has consumers attached, a backlog, an acknowledgement rate at or below
  the idle threshold, and a delivering count above zero
- **THEN** its verdict is `STALLED`

#### Scenario: Consumers are attached but nothing is dispatched

- **WHEN** a queue has consumers attached, a backlog, an acknowledgement rate at or below
  the idle threshold, and a delivering count of zero
- **THEN** its verdict is `STARVED`

#### Scenario: Each verdict names its likely cause

- **WHEN** a `STALLED` or `STARVED` verdict is reported
- **THEN** it is accompanied by the causes consistent with it — a blocked consumer or an
  unacknowledged message for `STALLED`, a selector or filter mismatch for `STARVED`

### Requirement: The broker's own slow-consumer verdict outranks the derived one

Where the broker has reported a consumer slow through its own detection, the system SHALL
report that as the verdict and name the consumer the broker identified, in preference to
any verdict Studio derived from sampled counters.

#### Scenario: A broker notification supersedes a derived verdict

- **WHEN** the broker has recently reported a slow consumer on a queue that Studio's own
  signals would classify as `FALLING_BEHIND`
- **THEN** the reported verdict is `BROKER_SLOW` and it carries the consumer name the
  broker supplied

#### Scenario: Derived detection is the fallback, and says so

- **WHEN** a verdict other than `BROKER_SLOW` is derived for a queue whose address has no
  broker-side slow-consumer detection configured
- **THEN** the verdict states that it is Studio's own derivation and that the broker has
  not been asked to judge

### Requirement: Every verdict carries the evidence it rests on

A verdict SHALL be accompanied by the values it was derived from: depth, the direction and
rate of the depth trend, enqueue rate, acknowledgement rate, the net of the two,
acknowledgement rate per consumer, messages in flight, and — where the verdict is
`DRAINING` — an estimated time to drain. A verdict SHALL NOT be presented as a bare label.

#### Scenario: A falling-behind verdict quantifies the shortfall

- **WHEN** a queue's verdict is `FALLING_BEHIND`
- **THEN** the enqueue rate, acknowledgement rate and their net are reported, so the
  operator can determine how much consumer capacity is missing

#### Scenario: Per-consumer velocity is reported where consumers are attached

- **WHEN** a verdict is reported for a queue with one or more consumers attached
- **THEN** the acknowledgement rate per consumer is among the evidence

### Requirement: A verdict states the age and span of the samples behind it

Every verdict SHALL report when its newest sample was taken and the period its rates were
measured over, so a rate averaged over minutes is not read as an instantaneous one.

#### Scenario: A slow-tier queue's rate names its window

- **WHEN** a verdict's rates are derived from samples spanning several minutes
- **THEN** the reported evidence states that span and the time of the newest sample

#### Scenario: A stale verdict is marked

- **WHEN** the snapshot a verdict rests on is older than the staleness threshold
- **THEN** the verdict is marked stale rather than presented as current

### Requirement: An uncomputable verdict is never reported as healthy

Where there are too few samples to derive a rate, the system SHALL report
`INSUFFICIENT_DATA` and state why. It SHALL NOT report the queue as `HEALTHY`, and SHALL
NOT substitute zero for a rate it could not compute.

#### Scenario: A newly created queue

- **WHEN** a queue has fewer than two samples within the evaluation window
- **THEN** its verdict is `INSUFFICIENT_DATA` and states that sampling has not yet
  established a rate

#### Scenario: Unknown ranks apart from healthy

- **WHEN** queues are ranked by severity
- **THEN** `INSUFFICIENT_DATA` queues are ranked apart from `HEALTHY` ones rather than
  folded into them

### Requirement: A partially reporting queue states its coverage

Where a queue is present on fewer nodes than the cluster has, or a node did not answer the
most recent sweep, the verdict SHALL state that its numbers cover only the reporting nodes.

#### Scenario: A queue missing from some nodes

- **WHEN** a verdict is computed for a queue present on fewer nodes than the cluster has
- **THEN** the verdict reports how many nodes contributed to it

#### Scenario: An unreachable node is not silently excluded

- **WHEN** a node did not answer the most recent sweep
- **THEN** verdicts covering queues on that node state that a node is unreachable rather
  than presenting the remaining nodes' numbers as complete

### Requirement: Consumer health is readable as a ranked cluster-wide view

The system SHALL expose the verdicts for a cluster as a paged, filterable collection
ordered by severity, worst first, so an operator can find the queues needing attention
without knowing their names in advance. A caller SHALL be able to request the verdict for a
single named queue.

#### Scenario: Worst first by default

- **WHEN** consumer health is requested for a cluster without an explicit ordering
- **THEN** the queues are returned ordered by verdict severity, worst first

#### Scenario: One queue in isolation

- **WHEN** consumer health is requested for one named queue
- **THEN** only that queue's verdict and evidence are returned

#### Scenario: Reading health requires cluster read access

- **WHEN** a caller without read access to a cluster requests its consumer health
- **THEN** the request is denied without revealing whether the cluster exists

### Requirement: The verdict is computed once and shared by every surface

The screen, the read API and the agent surface SHALL report the same verdict and evidence
for the same queue at the same moment, derived from one evaluation rather than
independently reimplemented per surface.

#### Scenario: The console and the agent agree

- **WHEN** a queue's verdict is read through the agent surface and through the read API
  within the same evaluation window
- **THEN** both report the same verdict and the same evidence values

### Requirement: Consumer health issues no broker call of its own

Verdicts SHALL be derived from state Studio has already collected. Reading consumer health
SHALL NOT cause a request to a broker.

#### Scenario: Reading health does not touch the broker

- **WHEN** consumer health is requested for a cluster, repeatedly and by many callers
- **THEN** no request is issued to any broker node as a result
