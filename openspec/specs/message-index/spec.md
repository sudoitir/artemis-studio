# message-index Specification

## Purpose
Defines the opt-in historical message index: a per-queue, retention-bounded copy of
observed messages held by Studio so that the SQL Console can answer questions about
messages the broker no longer has, and the contract that keeps that copy honest,
bounded, and easy to get rid of.

## Requirements

### Requirement: Indexing is opt-in, per queue, and never a default

The system SHALL NOT index any message unless an operator has created a subscription
naming the queues to capture. No queue SHALL be indexed as a side effect of running a
query, browsing a queue, or registering a cluster.

Creating, modifying or deleting a subscription SHALL require the settings write
permission and SHALL be audited, naming the queue pattern captured.

A subscription SHALL declare the queue pattern, the observation interval, and the
retention period. It SHALL record the time from which it began capturing.

#### Scenario: No subscription means no captured message

- **WHEN** an operator queries a queue that has no index subscription
- **THEN** the index holds no message for that queue and the query is answered from the live brokers

#### Scenario: Creating a subscription is audited

- **WHEN** an operator creates an index subscription
- **THEN** an audit record is written naming the queue pattern, the retention period and the operator

### Requirement: The index records what was observed, and says so

An indexed message SHALL carry the message's identity, its node, its address and queue, its
headers, its application properties, its body, and the time it was observed.

An indexed message SHALL record **how it was learned**: by sampled observation of a broker,
or by capture. The two make different claims and SHALL NOT be presented identically.

For a sampled message the index is a record of what was seen and not a record of everything
that existed, and the system SHALL NOT claim completeness for it. For a captured message the
index records everything routed to the address for as long as capture was active on that
node, and the system MAY claim completeness for a window that capture covered — but SHALL
NOT do so for any window or node it did not.

Where the system can estimate messages that passed through unrecorded — unobserved between
samples, or dropped by a capture that could not keep up — it SHALL report that figure.

An indexed message SHALL record when it was last still seen on its queue where that is
known, so that a result can distinguish a message observed once from one still present at
the most recent observation. For a captured message, which is recorded at routing time
rather than by looking at a queue, the system SHALL state that its presence on a queue is
not known rather than implying it has gone.

#### Scenario: An observed message is retrievable after consumption

- **WHEN** a message on an indexed queue is observed and then consumed
- **THEN** a query against the index still returns it, marked with when it was observed and when it was last seen

#### Scenario: A message consumed immediately is retrievable when captured

- **WHEN** a message is routed to a captured address and consumed before any poll could observe it
- **THEN** a query against the index returns it, marked as captured

#### Scenario: Completeness is never claimed

- **WHEN** an index-backed result contains sampled rows
- **THEN** it states that the index records observed messages and is not a complete capture

#### Scenario: Completeness is claimed only where capture covered it

- **WHEN** an index-backed result is returned
- **THEN** it claims completeness only for the nodes and the window that capture actually covered

#### Scenario: Sampled and captured rows are distinguishable

- **WHEN** a result contains rows learned by sampling and rows learned by capture
- **THEN** each row states which it is

### Requirement: A query outside the index's coverage warns rather than under-reporting

Coverage is per queue **and per node**. A node covers a queue from the time capture or
sampling began on that node until retention expires the oldest rows, and a node that was
live while carrying no tap contributes a gap in that coverage.

A query whose time window reaches before coverage began, whose target queue is not covered
by any subscription, or which reaches into a period during which a live node was uncovered,
SHALL state that the index cannot answer for that period, that queue, or that node.

The system SHALL NOT return a short result for an uncovered window, queue or node and
present it as the answer.

#### Scenario: A window predating capture warns

- **WHEN** a query asks the index for messages from before its subscription began capturing
- **THEN** the result states that the index has no coverage for that period

#### Scenario: An uncovered queue is named

- **WHEN** a query's FROM pattern matches an indexed queue and an unindexed one
- **THEN** the result names the queue the index cannot answer for, rather than silently returning only the indexed one

#### Scenario: A node that was live and uncovered is named

- **WHEN** a query's window overlaps a period during which a live node carried no tap
- **THEN** the result names that node and that period as uncovered

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
a time, and the maintenance that does so SHALL run without operator action.

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

### Requirement: The index is disposable and can be dropped in one action

The index is derived state. The system SHALL allow an operator to delete a
subscription and everything it captured in a single action, and SHALL state how many
messages that action will destroy before it is armed.

Deleting a subscription's captured messages SHALL NOT affect any broker, and SHALL be
audited.

Losing the index SHALL degrade the SQL Console to live-broker queries only; it SHALL
NOT prevent the console from working.

#### Scenario: Dropping an index states its blast radius

- **WHEN** an operator deletes an index subscription
- **THEN** the number of captured messages that will be destroyed is stated before the action can be confirmed

#### Scenario: A missing index does not break the console

- **WHEN** every index subscription has been deleted
- **THEN** the SQL Console continues to answer queries from the live brokers

### Requirement: Indexed payload is treated as retained data

The index holds message bodies, which are application payload. The system SHALL
require the message read permission to query the index, scoped to the caller's
clusters exactly as a live query is.

The interface that creates a subscription SHALL state, before it is confirmed, that
message bodies will be stored by Studio for the chosen retention period.

#### Scenario: The operator is told what is being stored

- **WHEN** an operator creates an index subscription
- **THEN** the interface states that message bodies will be stored for the chosen retention period before the subscription is created

#### Scenario: Index reads are permission-scoped

- **WHEN** an operator without message read permission on a cluster queries the index for that cluster
- **THEN** the query is refused
