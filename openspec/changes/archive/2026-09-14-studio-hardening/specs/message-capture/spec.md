## MODIFIED Requirements

### Requirement: Capture states its full blast radius before it can be armed

The interface that creates a capture subscription SHALL state, before it can be confirmed:
- the broker objects that will be created, and on which nodes;
- the addresses the pattern resolves to;
- the capture queue's bounds, in messages and in bytes;
- that Studio will store complete message bodies for the chosen retention period;
- how the capture will be removed.

These SHALL come from a dry run that resolves the pattern and the target nodes without
changing any broker.

Where the estate is configuration-managed, the system SHALL show the equivalent broker
configuration for an operator who would rather apply it permanently themselves.

#### Scenario: The operator is told what will be created

- **WHEN** an operator opens the capture creation dialog
- **THEN** it names the divert, the bounded capture queue, the address setting and the security setting that will be created, and the nodes they will be created on

#### Scenario: The configuration equivalent is offered

- **WHEN** an operator opens the capture creation dialog
- **THEN** the equivalent permanent broker configuration is shown alongside the runtime action

#### Scenario: A capture dry run changes nothing

- **WHEN** an operator previews a capture subscription
- **THEN** the resolved addresses, target nodes and bounds are returned, and no subscription is saved and no broker is changed

### Requirement: Capture never blocks, pages or grows a broker

The capture queue SHALL be bounded both to a fixed number of messages and to a fixed number
of bytes. The capture address SHALL be configured to drop rather than block, page or fail
when either bound is reached.

The system SHALL NOT be capable of causing a broker to block a producer, page to disk, or
grow without limit as a consequence of capture, whether or not Studio is draining the queue
and whether or not Studio's database is available.

A capture pattern SHALL NOT resolve to Studio's own capture queues or addresses. Capture
SHALL never tap its own taps.

#### Scenario: An undrained capture queue stays bounded

- **WHEN** Studio stops draining a capture queue and messages continue to be routed to the source address
- **THEN** the capture queue discards its oldest messages and the broker continues to accept and route production traffic unaffected

#### Scenario: Large messages are bounded by bytes

- **WHEN** messages much larger than average are routed to a captured address while the capture queue is not drained
- **THEN** the capture queue stays within its byte bound and discards rather than growing

#### Scenario: Production routing is unaffected by capture

- **WHEN** a message is routed to a captured address
- **THEN** it is delivered to its original destinations exactly as it would have been without capture

#### Scenario: A catch-all pattern does not capture the capture queues

- **WHEN** an operator captures with a pattern that would match every address
- **THEN** Studio's own capture addresses are excluded, and a pattern that can only match them is refused

### Requirement: Loss is measured and reported, never silent

Where the system cannot record every captured message, it SHALL report the estimated number
not recorded, per subscription and per node, with the cause named. This covers at least:
- the capture queue dropped messages;
- Studio could not keep up, including while a configured ingest rate limit slowed it;
- Studio's database was unavailable;
- a message could not be read.

A capture subscription that is losing messages SHALL be reported as degraded rather than as
healthy. It SHALL return to healthy once a full reconciliation interval passes without loss,
and the reported loss SHALL state the window it covers.

Where the loss cannot be estimated, for example because the divert filter selects only
part of the address's traffic, the system SHALL state that the estimate is unavailable. It
SHALL NOT report zero. An address with several bound queues SHALL NOT count the same routed
message once per queue.

#### Scenario: Dropped messages are counted

- **WHEN** a capture queue drops messages because it reached its bound
- **THEN** the subscription reports an estimate of how many messages were not recorded

#### Scenario: Sustained loss is visible as degradation

- **WHEN** a subscription is persistently unable to record everything routed to its address
- **THEN** it is presented as degraded, with the cause named, rather than as an active healthy capture

#### Scenario: Degradation clears when loss stops

- **WHEN** a degraded subscription records everything routed to its address for a full reconciliation interval
- **THEN** it is presented as active again, and earlier loss is shown with the window it occurred in

#### Scenario: A filtered capture states that loss cannot be estimated

- **WHEN** a capture subscription has a divert filter
- **THEN** its loss estimate is presented as unavailable, not as zero

#### Scenario: A multicast address is not over-counted

- **WHEN** a captured address has two bound queues and every routed message is recorded
- **THEN** no loss is reported

#### Scenario: A rate limit slows capture and discards nothing

- **WHEN** messages arrive faster than a subscription's ingest rate limit, including a backlog redelivered after Studio's database recovers
- **THEN** capture slows to the limit, the capture queue holds the backlog within its bound, and every message it still holds is stored; only messages the queue drops at its bound are counted as loss

### Requirement: Capture is per node and survives failover

A capture subscription SHALL be asserted on every live node of its cluster, and SHALL report
its state per node rather than as a single aggregate.

When a node becomes live that does not carry the tap — including a backup promoted by
failover — the system SHALL install the tap on it without operator action, and SHALL record
the period during which that node was live and uncaptured.

The system SHALL reinstall the tap without operator action, and record the gap, when:
- the tap's divert has disappeared from a live node, including when something outside
  Studio removed it; or
- the connection draining it has failed, including after a broker restart.

A node that is no longer live SHALL NOT keep a drain open.

#### Scenario: A promoted backup is captured

- **WHEN** a backup is promoted to live and does not carry the capture tap
- **THEN** the system installs the tap on it and the subscription reports that node as captured

#### Scenario: The gap created by failover is recorded

- **WHEN** a node was live for a period during which it carried no tap
- **THEN** that period is recorded as uncovered for that node, and a query reaching into it is told so

#### Scenario: Per-node state is reported

- **WHEN** the tap is installed on one node of a cluster and refused on another
- **THEN** the subscription reports both outcomes with the reason for the refusal, rather than a single combined status

#### Scenario: A removed divert is reinstalled

- **WHEN** a capture divert is removed from a live node by something other than Studio
- **THEN** the next reconciliation reinstalls it and records the uncovered period

#### Scenario: A drain whose connection failed is re-established

- **WHEN** the broker draining a capture queue restarts
- **THEN** the drain is re-established after the broker returns, and the subscription does not report the node as capturing in the meantime

### Requirement: Capture refuses rather than silently capturing nothing

Where a condition on the broker or in Studio's configuration would cause the tap to observe no
messages, to be undrainable, or to create an unsafe object, the system SHALL refuse to install
the tap and SHALL name the condition.

This SHALL include, at minimum:
- an existing exclusive divert on the source address, which would route messages away before
  the capture tap sees them;
- the absence of authority to restrict access to the capture queue;
- a node with no reachable Core endpoint, checked before any broker object is created;
- a broker identity for restricting capture queues that has not been configured.

A refusal that will not resolve without a change, such as an invalid filter or a missing
configuration, SHALL be reported as failed with its remedy. It SHALL NOT be retried and
re-audited on every reconciliation.

#### Scenario: An exclusive divert is refused with its reason

- **WHEN** an operator captures an address that already carries an exclusive divert
- **THEN** the request is refused, naming the exclusive divert and explaining that it would route messages away before capture sees them

#### Scenario: Capture is refused when it cannot be secured

- **WHEN** Studio lacks the authority to restrict access to the capture queue on a node
- **THEN** capture is refused on that node with the reason and the configuration that would grant it, and no capture queue is created there

#### Scenario: A node without a Core endpoint gets no tap

- **WHEN** a live node has no reachable Core endpoint
- **THEN** capture is refused on that node before any divert or capture queue is created, naming the missing endpoint

#### Scenario: Capture requires its broker identity to be configured

- **WHEN** the broker identity that capture queues are restricted to has not been configured
- **THEN** capture is refused with the setting that configures it, and no broker object is created

#### Scenario: A permanent refusal is not retried every pass

- **WHEN** a capture subscription's filter is rejected by the broker
- **THEN** the node is reported as failed with the reason, and later reconciliations neither retry the install nor write another audit event until the subscription changes

## ADDED Requirements

### Requirement: Capture is removable, and stays bounded when it is not removed

Deleting a capture subscription SHALL stop recording its messages before its stored rows are
removed. It SHALL remove every broker object the subscription created on every reachable
node, and SHALL state what it will destroy before it can be armed. A node unreachable at the
time SHALL be cleaned on its next reconciliation, and the outcome SHALL say so.

The system SHALL remove broker objects it created that no longer correspond to a
subscription. It SHALL NOT remove or modify capture objects or settings created by another
Studio instance; each instance's capture settings SHALL be scoped to that instance.

Where Studio is not running to perform that removal, the tap SHALL remain bounded by its
capture queue's message and byte bounds and its expiry. It persists across broker restarts
until Studio or an operator removes it.

#### Scenario: Deleting a subscription removes the tap

- **WHEN** an operator deletes a capture subscription
- **THEN** the divert, capture queue, address setting and security setting created for it are removed from every reachable node

#### Scenario: Deletion leaves no rows written after it

- **WHEN** an operator deletes a capture subscription while messages are still arriving
- **THEN** recording stops before stored rows are deleted, and no captured row for it remains afterwards

#### Scenario: An orphaned tap is reclaimed

- **WHEN** a capture object created by this Studio instance has no corresponding subscription
- **THEN** the system removes it without operator action

#### Scenario: Another instance's tap is left alone

- **WHEN** a capture object created by a different Studio instance is present on a broker
- **THEN** the system does not remove or modify it or the settings that bound it

#### Scenario: An unremoved tap stays bounded

- **WHEN** Studio is not running and its broker restarts
- **THEN** any capture queue that remains is still bounded by its message and byte bounds and its expiry

## MODIFIED Requirements

### Requirement: Capture is bounded in what it stores as well as what it observes

A captured message body larger than the configured per-message limit SHALL be stored
truncated and marked as truncated, and SHALL NOT be transferred to Studio in full.

A capture subscription SHALL be bounded by a stored-size limit as well as by a retention
period, and SHALL report what it currently holds against both. What a subscription holds,
enforces its size limit against, removes on delete, and ages out by retention SHALL be the
rows captured for the addresses it covers. This SHALL hold even where an address's name
differs from the names of the queues bound to it.

Changing a live subscription's bounds or filter SHALL take effect on the broker. The capture
objects SHALL be re-created with the new values, the change SHALL be audited with the old and
new values, and the gap SHALL be recorded.

#### Scenario: An oversized body is truncated, not streamed

- **WHEN** a captured message's body exceeds the configured per-message limit
- **THEN** it is stored truncated and marked as truncated

#### Scenario: A subscription reports both bounds

- **WHEN** an operator views a capture subscription
- **THEN** it reports the messages and bytes held against both its retention period and its size limit

#### Scenario: A multicast address's footprint is counted

- **WHEN** a subscription captures a multicast address whose bound queues have different names from the address
- **THEN** its reported holdings, size-limit enforcement, deletion and retention all include those captured rows

#### Scenario: Narrowing a filter takes effect

- **WHEN** an operator narrows the filter of a live capture subscription
- **THEN** the broker's capture divert uses the new filter, the change is audited with the old and new filter, and the gap is recorded

## ADDED Requirements

### Requirement: A captured message is acknowledged only after it is stored

The system SHALL acknowledge a captured message on the broker only after it has been durably
stored, or after it has been counted as loss with its cause.

If storing fails, the messages SHALL remain on the capture queue. Draining SHALL pause and
retry with increasing delay, the node SHALL be reported as degraded with the cause, and
draining SHALL resume without operator action once storing succeeds. A message redelivered
after an unacknowledged write SHALL NOT be stored twice.

A message that repeatedly cannot be read SHALL be counted as loss with its cause before it is
acknowledged. It SHALL NOT be skipped silently. Stopping a drain SHALL store what it has
received before acknowledging it.

#### Scenario: A database outage loses nothing within the bound

- **WHEN** Studio's database is unavailable for a period during which fewer messages are routed than the capture queue's bound, and then becomes available
- **THEN** every message routed during the outage is stored once, and none is acknowledged before it was stored

#### Scenario: A database outage beyond the bound is reported

- **WHEN** Studio's database is unavailable long enough that the capture queue reaches its bound
- **THEN** the dropped messages are counted as loss with the database outage named as the cause, and production traffic is unaffected

#### Scenario: Draining pauses while storing fails

- **WHEN** storing captured messages fails
- **THEN** the drain pauses and retries with increasing delay rather than consuming continuously, and the node is reported as degraded with the cause

#### Scenario: One drain's acknowledgement never covers another drain's unstored rows

- **WHEN** two capture queues are drained concurrently and one of their writes is slow or fails
- **THEN** each drain acknowledges only messages whose own rows have been stored

#### Scenario: An unreadable message is counted

- **WHEN** a captured message cannot be read after repeated attempts
- **THEN** it is counted as loss with the cause named before it is acknowledged

## REMOVED Requirements

### Requirement: Capture is removable, and bounded even when it is not removed
**Reason**: Its scenario "A tap does not outlive a broker restart" contradicts ADR-0062 and ADR-0065. A divert created over management persists across a broker restart. The requirement also let one instance remove settings another instance's capture depends on, and let a delete leave rows written after it.
**Migration**: Replaced by "Capture is removable, and stays bounded when it is not removed". An unremoved tap persists across restarts, bounded by its capture queue's message and byte bounds and its expiry. Settings are scoped per instance, and recording stops before rows are deleted.
