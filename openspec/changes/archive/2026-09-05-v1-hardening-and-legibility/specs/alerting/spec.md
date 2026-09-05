## MODIFIED Requirements

### Requirement: A metric-threshold rule evaluates the current value per matching subject

For a gauge metric (queue depth, consumer count, delivering count, scheduled count),
the system SHALL evaluate the rule against each matching subject's most recently
scraped value. For a rate metric (messages added, messages acked), the system SHALL
evaluate the rule against each matching subject's throughput computed over a recent
window, using the same restart-safe never-negative computation the metrics capability
already uses for chart rates. For the derived acknowledgement-rate-per-consumer metric,
the system SHALL evaluate as defined by the slow-consumer requirement below. A subject
with fewer than two samples in the rate window SHALL be treated as having no evaluable
value for that tick, neither active nor resolving.

#### Scenario: A depth threshold fires per queue

- **WHEN** a rule thresholds queue depth and several queues exceed it
- **THEN** each exceeding queue is evaluated and tracked independently under the same
  rule

#### Scenario: A brand-new queue does not falsely trigger a rate rule

- **WHEN** a queue is scraped for the first time and has not yet accumulated two
  samples in the rate window
- **THEN** a rate-threshold rule produces no verdict for that queue this tick, neither
  firing nor resolving

#### Scenario: A counter reset never triggers a false negative-rate reading

- **WHEN** a monotonic counter backing a rate metric resets (a broker restart) within
  the evaluation window
- **THEN** the computed rate for that subject is never negative

## ADDED Requirements

### Requirement: A slow-consumer rule evaluates acknowledgement rate per consumer

The system SHALL offer an acknowledgement-rate-per-consumer metric whose subject universe
is restricted to queue subjects that **both** have at least one consumer attached and a
non-zero message backlog at their most recent scrape. A queue with no consumers, or with
consumers but no backlog, SHALL NOT be a subject of this metric — it is idle, not slow.

The metric's value SHALL be the queue's acknowledgement throughput over the same recent
window used by rate metrics, divided by its consumer count, and SHALL inherit that
window's restart-safe never-negative computation, so that a broker restart resetting the
underlying counter cannot produce a firing. A subject with fewer than two samples in the
window SHALL be absent from the evaluation rather than treated as zero.

Subjects SHALL be keyed the same way as other queue-scoped metric subjects, including
when the rule is scoped to a node.

#### Scenario: A queue with no backlog is not a subject

- **WHEN** a queue has consumers attached and no messages
- **THEN** it is not evaluated by a slow-consumer rule

#### Scenario: A queue with no consumers is not a subject

- **WHEN** a queue has a backlog and no consumers attached
- **THEN** it is not evaluated by a slow-consumer rule

#### Scenario: Attached, backlogged and not draining

- **WHEN** a queue has consumers attached, a non-zero backlog, and an acknowledgement
  rate per consumer below the rule's threshold
- **THEN** the rule is active for that queue

#### Scenario: Attached, backlogged and draining

- **WHEN** the same queue's acknowledgement rate per consumer is above the threshold
- **THEN** the rule is evaluated for that queue and is not active

#### Scenario: A counter reset does not fire a slow-consumer rule

- **WHEN** the acknowledgement counter backing the metric resets within the window
- **THEN** the computed rate is not negative and no firing is produced by the reset

#### Scenario: One sample in the window

- **WHEN** a subject has only one sample in the rate window
- **THEN** it produces no verdict this tick, neither firing nor resolving

### Requirement: Studio's own slow-consumer detection states its attribution limit

Where the system derives slow-consumer state itself, it SHALL attribute that state to a
queue on a node and SHALL state in the interface that it cannot attribute it to an
individual consumer, because the broker's consumer listing carries no per-consumer
acknowledgement counter. Attribution to a named consumer SHALL come only from the
broker's own slow-consumer notification.

#### Scenario: The limit is disclosed, not implied

- **WHEN** an operator views a slow-consumer firing derived by Studio
- **THEN** the firing names the queue and node and states that per-consumer attribution
  is available only from the broker's own detection

### Requirement: Slow-consumer detection ships a rule template, not a seeded rule

The system SHALL NOT seed a slow-consumer rule on cluster registration. It SHALL instead
offer a prefilled slow-consumer rule template in the rule-creation interface, because a
meaningful threshold is workload-specific and any seeded value would be wrong for most
deployments.

#### Scenario: No rule is created without an operator

- **WHEN** a cluster is registered
- **THEN** no slow-consumer rule exists for it

#### Scenario: The template is offered

- **WHEN** an operator creates a new rule
- **THEN** a prefilled slow-consumer template is offered as a starting point
