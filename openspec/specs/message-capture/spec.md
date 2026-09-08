# message-capture Specification

## Purpose
Defines complete message capture: an opt-in, per-queue tap that copies every message routed
to an address into a Studio-owned, bounded queue so that the SQL Console, the message index
and request-reply correlation observe what actually passed through rather than what happened
to still be sitting on a queue when Studio last looked.

## Requirements

### Requirement: Capture is opt-in, per queue, and never a side effect

The system SHALL NOT capture any message unless an operator has created a capture
subscription naming the queue. Running a query, browsing a queue, tailing a queue, or
registering a cluster SHALL NOT create one.

Creating, modifying or deleting a capture subscription SHALL require a permission distinct
from the one that governs Studio's own settings, because capture mutates the broker. It
SHALL be audited, naming the queue, the retention period and the operator.

#### Scenario: No subscription means no broker mutation

- **WHEN** an operator queries or tails a queue that has no capture subscription
- **THEN** no divert, capture queue, address setting or security setting is created on any broker

#### Scenario: Creating a capture subscription is audited

- **WHEN** an operator creates a capture subscription
- **THEN** an audit record is written naming the queue, the retention period and the operator, before the broker is contacted

### Requirement: Capture states its full blast radius before it can be armed

The interface that creates a capture subscription SHALL state, before it can be confirmed:
the broker objects that will be created and on which nodes; that Studio will store complete
message bodies for the chosen retention period; and how the capture will be removed.

Where the estate is configuration-managed, the system SHALL show the equivalent broker
configuration for an operator who would rather apply it permanently themselves.

#### Scenario: The operator is told what will be created

- **WHEN** an operator opens the capture creation dialog
- **THEN** it names the divert, the bounded capture queue, the address setting and the security setting that will be created, and the nodes they will be created on

#### Scenario: The configuration equivalent is offered

- **WHEN** an operator opens the capture creation dialog
- **THEN** the equivalent permanent broker configuration is shown alongside the runtime action

### Requirement: Capture never blocks, pages or grows a broker

The capture queue SHALL be bounded to a fixed number of messages, and the capture address
SHALL be configured to drop rather than block, page or fail when that bound is reached.

The system SHALL NOT be capable of causing a broker to block a producer, page to disk, or
grow without limit as a consequence of capture, whether or not Studio is draining the queue.

#### Scenario: An undrained capture queue stays bounded

- **WHEN** Studio stops draining a capture queue and messages continue to be routed to the source address
- **THEN** the capture queue discards its oldest messages and the broker continues to accept and route production traffic unaffected

#### Scenario: Production routing is unaffected by capture

- **WHEN** a message is routed to a captured address
- **THEN** it is delivered to its original destinations exactly as it would have been without capture

### Requirement: Loss is measured and reported, never silent

Where the system cannot record every captured message — because the capture queue dropped
messages, because Studio could not keep up, or because a configured ingest rate limit was
reached — it SHALL report the estimated number not recorded, per subscription.

A capture subscription that is losing messages SHALL be reported as degraded rather than as
healthy.

#### Scenario: Dropped messages are counted

- **WHEN** a capture queue drops messages because it reached its bound
- **THEN** the subscription reports an estimate of how many messages were not recorded

#### Scenario: Sustained loss is visible as degradation

- **WHEN** a subscription is persistently unable to record everything routed to its address
- **THEN** it is presented as degraded, with the cause named, rather than as an active healthy capture

### Requirement: Capture is per node and survives failover

A capture subscription SHALL be asserted on every live node of its cluster, and SHALL report
its state per node rather than as a single aggregate.

When a node becomes live that does not carry the tap — including a backup promoted by
failover — the system SHALL install the tap on it without operator action, and SHALL record
the period during which that node was live and uncaptured.

#### Scenario: A promoted backup is captured

- **WHEN** a backup is promoted to live and does not carry the capture tap
- **THEN** the system installs the tap on it and the subscription reports that node as captured

#### Scenario: The gap created by failover is recorded

- **WHEN** a node was live for a period during which it carried no tap
- **THEN** that period is recorded as uncovered for that node, and a query reaching into it is told so

#### Scenario: Per-node state is reported

- **WHEN** the tap is installed on one node of a cluster and refused on another
- **THEN** the subscription reports both outcomes with the reason for the refusal, rather than a single combined status

### Requirement: Capture is address-scoped and says what that means

A capture tap observes messages as they are routed to an address, before they are
distributed to the queues bound to that address. Where an address has more than one bound
queue, the system SHALL state that captured rows record what was routed to the address and
do not record which of its queues received them.

The system SHALL NOT present an address-scoped capture as though it were a per-queue record.

#### Scenario: A multicast address states its scope

- **WHEN** a query returns captured rows for a queue bound to an address with more than one queue
- **THEN** the result states that the rows record what was routed to the address, not what that queue received

### Requirement: Capture refuses rather than silently capturing nothing

Where a condition on the broker would cause the tap to observe no messages or to create an
unsafe object, the system SHALL refuse to install the tap and SHALL name the condition.

This SHALL include, at minimum: an existing exclusive divert on the source address, which
would route messages away before the capture tap sees them; and the absence of authority to
restrict access to the capture queue.

#### Scenario: An exclusive divert is refused with its reason

- **WHEN** an operator captures an address that already carries an exclusive divert
- **THEN** the request is refused, naming the exclusive divert and explaining that it would route messages away before capture sees them

#### Scenario: Capture is refused when it cannot be secured

- **WHEN** Studio lacks the authority to restrict access to the capture queue on a node
- **THEN** capture is refused on that node with the reason and the configuration that would grant it, and no capture queue is created there

### Requirement: The captured copy is protected on the broker

A capture queue holds a complete copy of the traffic routed to its source address. The
system SHALL restrict access to it to Studio's own broker identity, and SHALL NOT create a
capture queue it has been unable to restrict.

#### Scenario: The capture queue is restricted at creation

- **WHEN** a capture tap is installed
- **THEN** access to the capture queue is restricted to Studio's broker identity as part of the same installation

### Requirement: Capture is removable, and bounded even when it is not removed

Deleting a capture subscription SHALL remove every broker object the subscription created,
on every node, and SHALL state what it will destroy before it can be armed.

The system SHALL remove broker objects it created that no longer correspond to a
subscription, and SHALL NOT remove capture objects created by another Studio instance.

Where Studio is not running to perform that removal, the tap SHALL remain bounded and SHALL
cease to exist when the broker restarts.

#### Scenario: Deleting a subscription removes the tap

- **WHEN** an operator deletes a capture subscription
- **THEN** the divert, capture queue, address setting and security setting created for it are removed from every node

#### Scenario: An orphaned tap is reclaimed

- **WHEN** a capture object created by this Studio instance has no corresponding subscription
- **THEN** the system removes it without operator action

#### Scenario: Another instance's tap is left alone

- **WHEN** a capture object created by a different Studio instance is present on a broker
- **THEN** the system does not remove or modify it

#### Scenario: A tap does not outlive a broker restart

- **WHEN** Studio is not running and its broker restarts
- **THEN** the capture tap no longer exists on that broker

### Requirement: Capture is bounded in what it stores as well as what it observes

A captured message body larger than the configured per-message limit SHALL be stored
truncated and marked as truncated, and SHALL NOT be transferred to Studio in full.

A capture subscription SHALL be bounded by a stored-size limit as well as by a retention
period, and SHALL report what it currently holds against both.

#### Scenario: An oversized body is truncated, not streamed

- **WHEN** a captured message's body exceeds the configured per-message limit
- **THEN** it is stored truncated and marked as truncated

#### Scenario: A subscription reports both bounds

- **WHEN** an operator views a capture subscription
- **THEN** it reports the messages and bytes held against both its retention period and its size limit

### Requirement: Capture does not claim delivery

A captured message records that a message was routed to an address. It SHALL NOT be
presented as evidence that the message was delivered to, or consumed from, any queue bound
to that address.

#### Scenario: Capture is not presented as delivery

- **WHEN** a captured row is displayed
- **THEN** it is described as what was routed to the address, and no interface element asserts that it was delivered or consumed

### Requirement: Only one Studio instance reconciles a cluster's capture

Where more than one Studio instance is running against the same estate, at most one SHALL
perform capture reconciliation for a given cluster at a time.

#### Scenario: A second instance does not reconcile concurrently

- **WHEN** two Studio instances run against the same cluster
- **THEN** only one performs capture reconciliation for it, and the other does not create or destroy that cluster's capture objects
