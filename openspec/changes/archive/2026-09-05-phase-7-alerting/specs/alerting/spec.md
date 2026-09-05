## Purpose

Defines how an operator sets a condition worth being told about — a metric crossing a
threshold, or a cluster entering a bad HA state — and how Studio debounces, tracks,
and delivers that condition to a webhook or Slack channel until it clears.

## ADDED Requirements

### Requirement: An alert rule is either a metric threshold or a state condition

The system SHALL support two kinds of alert rule: a metric-threshold rule, which
compares a named metric's current value for each matching subject against a threshold
using a comparator; and a state-condition rule, which watches a fixed, closed set of
cluster health transitions (split-brain, node down, replication behind, cluster
degraded). A rule SHALL be exactly one kind, and creating or updating a rule with both
or neither kind's fields populated SHALL be rejected.

#### Scenario: A threshold rule is created

- **WHEN** an operator creates a rule with a metric, comparator, and threshold
- **THEN** the rule is stored as a metric-threshold rule and no state condition is
  accepted alongside it

#### Scenario: A state rule is created

- **WHEN** an operator creates a rule naming a state condition (split-brain, node down,
  replication behind, or cluster degraded)
- **THEN** the rule is stored as a state-condition rule and no metric/comparator/
  threshold is accepted alongside it

#### Scenario: A malformed rule is rejected

- **WHEN** a rule is submitted with both a metric and a state condition, or with
  neither
- **THEN** the system rejects the request without creating a rule

### Requirement: A metric-threshold rule evaluates the current value per matching subject

For a gauge metric (queue depth, consumer count, delivering count, scheduled count),
the system SHALL evaluate the rule against each matching subject's most recently
scraped value. For a rate metric (messages added, messages acked), the system SHALL
evaluate the rule against each matching subject's throughput computed over a recent
window, using the same restart-safe never-negative computation the metrics capability
already uses for chart rates. A subject with fewer than two samples in the rate window
SHALL be treated as having no evaluable value for that tick, neither active nor
resolving.

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

### Requirement: A state-condition rule evaluates cluster health, not a metric

A split-brain rule SHALL fire only when the cluster's split-brain status is corroborated
critical, never on a first-sighting suspected status. A node-down rule SHALL fire when a
manageable node is unreachable or reports stopped. A replication-behind rule SHALL fire
when a backup node's replication is not in sync. A cluster-degraded rule SHALL fire
when the cluster's overall health level is degraded or worse.

#### Scenario: A first-sighting split-brain does not fire

- **WHEN** two nodes are seen live within the same refresh cycle for the first time
- **THEN** a split-brain rule does not fire, since the condition is only suspected, not
  yet corroborated

#### Scenario: A corroborated split-brain fires

- **WHEN** the same dual-active condition is corroborated on a later refresh cycle
- **THEN** a split-brain rule for that cluster fires

### Requirement: A rule debounces through a PENDING state for a configured duration

Each rule SHALL have a configurable duration; a condition becoming active SHALL move
that (rule, subject) from OK to PENDING, and only to FIRING once the condition has
remained continuously active for that duration. A duration of zero SHALL fire
immediately on the condition becoming active. The condition becoming false at any point
before the duration elapses SHALL return the (rule, subject) to OK, resetting the
elapsed time.

#### Scenario: A brief threshold breach does not fire

- **WHEN** a metric crosses a threshold for less than the rule's configured duration
  and then returns below it
- **THEN** the rule never reaches FIRING for that subject

#### Scenario: A sustained breach fires once the duration elapses

- **WHEN** a metric stays across a threshold continuously for at least the rule's
  configured duration
- **THEN** the rule transitions to FIRING for that subject at the moment the duration
  elapses

#### Scenario: A zero-duration rule fires immediately

- **WHEN** a rule's configured duration is zero and its condition becomes active
- **THEN** the rule fires for that subject on the same evaluation

#### Scenario: The debounce survives a restart

- **WHEN** Studio restarts while a (rule, subject) is PENDING
- **THEN** the elapsed time toward FIRING is preserved and evaluation resumes from
  where it left off

### Requirement: A firing resolves when its condition is no longer active

The system SHALL transition a FIRING (rule, subject) back to OK the first evaluation on
which its condition is no longer active, and SHALL record the resolution time. A
subject that disappears entirely (for example, a deleted queue) while PENDING or FIRING
SHALL be treated as resolved rather than left in a state that can never change again.

#### Scenario: A cleared threshold resolves

- **WHEN** a firing metric-threshold subject's value returns to the non-alerting side
  of the threshold
- **THEN** that (rule, subject) resolves on the same evaluation

#### Scenario: A deleted subject resolves rather than hangs

- **WHEN** a queue with a PENDING or FIRING alert state is deleted from the broker
- **THEN** its alert state resolves and is removed rather than remaining indefinitely

### Requirement: Every firing and resolution is recorded in a durable history

The system SHALL append a history record each time a (rule, subject) starts firing and
each time it resolves, including the rule, the subject, the severity, the observed
value, and the start and resolution timestamps. This history SHALL be independently
queryable from the current firing state.

#### Scenario: History survives after resolution

- **WHEN** a firing has resolved
- **THEN** an operator can still see when it started, what value triggered it, and when
  it resolved

### Requirement: A firing rule delivers to its bound notification channels

A rule SHALL be routable to zero or more notification channels. When a rule's
evaluation produces one or more new firings or resolutions in a single evaluation
tick, the system SHALL queue exactly one notification per bound channel for that
tick, describing every affected subject in that one notification — never one
notification per subject.

#### Scenario: Many subjects crossing at once produce one notification

- **WHEN** a single evaluation causes 50 subjects under one rule to start firing
  simultaneously
- **THEN** each bound channel receives exactly one notification for that evaluation,
  listing all 50 subjects

#### Scenario: An unrouted rule still tracks state

- **WHEN** a rule has no bound channels
- **THEN** it still evaluates, debounces, fires, resolves, and records history — it
  simply delivers nothing

### Requirement: Notification delivery is retried and durable across a restart

A queued notification SHALL be retried with increasing delay on failure, up to a
bounded number of attempts, after which it SHALL be marked permanently failed rather
than retried forever. Queued notifications SHALL survive a Studio restart and resume
being retried afterward.

#### Scenario: A transient failure is retried

- **WHEN** a notification delivery attempt fails with a server error
- **THEN** the system retries after a delay, increasing on each subsequent failure

#### Scenario: Repeated failure gives up

- **WHEN** a notification has failed delivery the maximum configured number of times
- **THEN** it is marked permanently failed and no further attempts are made

#### Scenario: A pending notification survives a restart

- **WHEN** Studio restarts while a notification delivery is queued or awaiting retry
- **THEN** the notification is still retried after Studio comes back up

### Requirement: A Slack channel delivers a readable message

A Slack channel SHALL deliver notifications via an incoming webhook with a
human-readable summary. An invalid or revoked webhook SHALL be treated as
permanently failed without retry.

#### Scenario: A revoked Slack webhook is not retried

- **WHEN** a Slack channel's webhook URL has been revoked
- **THEN** the delivery is marked permanently failed on the first attempt, without
  retrying

### Requirement: A webhook channel's delivery is signed and rate-limit aware

A generic webhook channel SHALL sign each delivery so the receiver can verify it
originated from Studio and was not replayed or altered, and SHALL include an
identifier a receiver can use to deduplicate retried deliveries. The system SHALL
honor a receiver's request to slow down before retrying.

#### Scenario: A receiver can verify the signature

- **WHEN** a webhook channel receives a delivery
- **THEN** it can compute a matching signature over the delivery id, timestamp, and
  body using the channel's shared secret

#### Scenario: A rate-limited receiver is respected

- **WHEN** a webhook receiver responds indicating the sender should wait before
  retrying
- **THEN** the next retry attempt is delayed at least as long as requested

### Requirement: A notification channel's secret is never exposed in plaintext after creation

The system SHALL store a notification channel's secret material (a Slack webhook URL,
a webhook signing secret) encrypted at rest, SHALL accept it only on create or update,
and SHALL NOT return it in plaintext from any read.

#### Scenario: Reading a channel does not reveal its secret

- **WHEN** an operator reads a previously created channel's configuration
- **THEN** the secret material is masked, not returned in plaintext

#### Scenario: An operator can test a channel without guessing its secret

- **WHEN** an operator triggers a test notification on an existing channel
- **THEN** Studio sends the test using the stored secret without requiring the
  operator to re-enter it

### Requirement: Rule and channel changes are audited; firings and deliveries are not

Creating, updating, deleting, or testing an alert rule or a notification channel SHALL
be recorded as an audited operator action. A rule firing, resolving, or a notification
delivery attempt SHALL NOT create an audit event — these are recorded in the alert
history and delivery ledger instead.

#### Scenario: Editing a rule is audited

- **WHEN** an operator changes a rule's threshold
- **THEN** an audit event records who changed it and what changed

#### Scenario: A firing does not create an audit event

- **WHEN** a rule transitions to FIRING
- **THEN** no audit event is created for that transition; it appears only in the alert
  firing history

### Requirement: Built-in critical rules are seeded per cluster and remain ordinary rules

When a cluster is registered, the system SHALL seed split-brain, node-down, and
replication-behind state-condition rules for it. These seeded rules SHALL behave as
ordinary rules — editable, routable to channels, and disableable — not as
unconditional checks outside the rule model.

#### Scenario: A newly registered cluster has built-in rules

- **WHEN** a cluster is registered
- **THEN** split-brain, node-down, and replication-behind rules exist for it,
  unbound to any channel and enabled by default

#### Scenario: A built-in rule can be silenced

- **WHEN** an operator disables the seeded replication-behind rule for a cluster
- **THEN** it no longer evaluates or fires for that cluster until re-enabled

### Requirement: The alerts screen shows current firings, history, and rule management

The system SHALL provide a screen listing currently firing alerts for a cluster, a
browsable history of past firings and resolutions, and management of rules and their
channel bindings. Firing alerts SHALL also be reflected as an indicator on the
affected node in the cluster's topology view, and as a count visible outside the
current cluster's view.

#### Scenario: A firing alert appears on the topology graph

- **WHEN** a state-condition rule scoped to a node is firing
- **THEN** that node's topology graph representation shows a firing indicator

#### Scenario: An operator sees firing counts while viewing another cluster

- **WHEN** an operator is viewing a cluster other than the one with active firings
- **THEN** a firing count for the other cluster is still visible somewhere in the
  application shell
