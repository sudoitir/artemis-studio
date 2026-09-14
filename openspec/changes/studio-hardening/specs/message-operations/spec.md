## MODIFIED Requirements

### Requirement: A queue's messages can be browsed with full headers and properties

The system SHALL expose a paged read of the messages on one queue of one node,
accepting an optional Artemis filter expression. Each returned message SHALL
carry its message id, type, durability, priority, timestamp, expiration, size,
the count of application properties, its correlation id, and its reply-to
destination when the message has one. A single-message read SHALL additionally
return the full header set and the string, integer, long, and boolean property
maps, and the message body.

Every value returned by a browse or a single-message read SHALL be governed by the
content policy for the caller: sensitive values masked or dropped, uninspectable
content withheld, and each masked, dropped, withheld or clear-by-grant value
identified with its location and class. A body preview SHALL be derived from the
governed body, never from the broker's body.

The browse SHALL be served over the Core client when the cluster has an
available Core connection, and over Jolokia otherwise. Over the Core client the
body SHALL be returned faithfully — text as text, binary as bytes with an
encoding indicator — and application properties SHALL keep their real types.
Over Jolokia the browse SHALL cost exactly one batched Jolokia POST per call
(the `browse` exec and the `MessageCount` read in one array). Every browse and
single-message response SHALL state which channel served it.

Over the Core client, because a queue browser has no server-side offset, a
requested page beyond a bounded browse depth SHALL be served over Jolokia
instead, and the response SHALL state that it was. A Core browse SHALL read no
more messages from the broker than the requested page requires. It SHALL NOT read
the rest of the queue to count it.

The response's total SHALL come from the broker's own count: the queue's message count
when there is no filter, or the broker's count of messages matching the filter. Where
that count is not available, the response SHALL state that the total is unavailable
and why. It SHALL NOT report a guessed or partial number, and SHALL NOT report zero.

A filter expression the broker rejects SHALL be reported as invalid without
repeating the expression's text, because a filter can carry sensitive literals.

#### Scenario: Browse returns a page

- **WHEN** an operator browses a queue that holds more messages than the page size
- **THEN** the response contains one page of message summaries and either the queue's total message count or a statement that the total is unavailable

#### Scenario: A Core browse of a deep queue reads only its page

- **WHEN** an operator requests the first page of a queue holding many thousands of messages over the Core client
- **THEN** no more messages than that page requires are read from the broker, and the total comes from the broker's count

#### Scenario: An unavailable total is stated, not zero

- **WHEN** the broker's count for a browse cannot be obtained
- **THEN** the response states that the total is unavailable and why, and does not report zero

#### Scenario: Browse honours a filter

- **WHEN** an operator browses with an Artemis filter expression
- **THEN** only messages matching the filter are returned

#### Scenario: Core channel returns a faithful body

- **WHEN** a queue with an available Core connection holds a message with a binary body and the caller holds clear access
- **THEN** the single-message read returns the exact bytes with a binary encoding indicator and states that the Core channel served it

#### Scenario: Single POST per browse

- **WHEN** a browse is served over Jolokia
- **THEN** exactly one Jolokia POST is issued to the target node for that browse and the response states the Jolokia channel served it

#### Scenario: Deep page falls back to Jolokia

- **WHEN** an operator requests a page beyond the bounded Core browse depth on a cluster with a Core connection
- **THEN** that page is served over Jolokia and the response states the channel changed

#### Scenario: Node is explicit or defaulted to the live node

- **WHEN** a browse request omits the node
- **THEN** the message-holding live node of the logical node serving that queue is used, and the response states which node answered

#### Scenario: A message's reply-to destination is exposed

- **WHEN** a browsed message carries a JMS reply-to destination
- **THEN** the browse response includes that destination for the message

#### Scenario: Browsed content is governed

- **WHEN** a user without clear access browses a queue whose messages carry an `Authorization` property and an email in the body
- **THEN** the summaries' body previews and the single-message read show the credential dropped and the email redacted, each identified by location and class

#### Scenario: An invalid filter is not echoed

- **WHEN** an operator browses with a filter the broker rejects
- **THEN** the response states that the filter is invalid and does not contain the filter text

### Requirement: Messages can be moved, retried, deleted, or expired by ids or by filter

The system SHALL expose move, retry, delete, and expire operations on the
messages of one queue, targeted either by an explicit list of message ids or by
an Artemis filter expression. Move SHALL take a target queue. The affected-count
result SHALL be the broker's own operation result where the broker returns one,
and otherwise the number of ids acted on.

An operation by ids SHALL send its ids to the broker in bounded batches, never one request
per id. When it fails after acting on some ids, the result SHALL be reported as partial:
- the count affected before the failure;
- the ids not attempted;
- the error.

It SHALL NOT be reported as a plain failure that implies nothing changed.

#### Scenario: Delete by ids

- **WHEN** an operator deletes three messages by id
- **THEN** those messages are removed from the queue and the affected count is
  reported

#### Scenario: Move by filter

- **WHEN** an operator moves every message matching a filter to another queue
- **THEN** the matching messages are moved and the affected count is reported

#### Scenario: Retry replays from a dead-letter queue

- **WHEN** an operator retries messages on a dead-letter queue
- **THEN** each is returned to its original address

#### Scenario: A large id list is batched

- **WHEN** an operator deletes several hundred messages by id
- **THEN** the ids are sent to the broker in bounded batches rather than one request per id

#### Scenario: A failure part-way is reported as partial

- **WHEN** a move by ids fails after some of the ids were moved
- **THEN** the result is partial and states how many were moved, which ids were not attempted, and the error

### Requirement: Every mutation writes an audit event in its own transaction

Every message mutation SHALL write an `audit_event` row that is committed with a pending
outcome before the broker call, in its own transaction, and updated after the call to
success with the affected count or to failure with the error. After a partial failure, the
row SHALL also carry the count affected before it.

A dry run SHALL also be audited, marked as a dry run with a success outcome. A broker
failure SHALL still leave a committed failure row and SHALL be returned to the caller as a
structured problem response.

#### Scenario: Successful mutation is audited

- **WHEN** a move succeeds
- **THEN** a committed `audit_event` records the action, target queue, actor,
  affected count, and a success outcome

#### Scenario: Failed mutation is audited

- **WHEN** the broker rejects a delete
- **THEN** a committed `audit_event` records the failure and its error, and the
  caller receives a structured problem response

#### Scenario: Partial mutation is audited with its count

- **WHEN** a delete by ids fails after removing some of the ids
- **THEN** a committed `audit_event` records the failure, the error, and the count removed before the failure

#### Scenario: Dry run is audited

- **WHEN** a mutation runs in dry-run mode
- **THEN** a committed `audit_event` marked as a dry run records the estimated
  count

### Requirement: Message operations require message-IO capability and are rate-limited

The system SHALL gate the message views on the connection's `MESSAGE_IO`
capability, rendering the capability reason and `broker.xml` guidance rather than
hiding the controls when it is unavailable. Every Jolokia request a message operation
issues, including each batch of a by-ids operation and the count read of a browse, SHALL
pass through the per-node management-call rate limiter.

Selecting the Core client for a browse, single-message read, or send SHALL NOT change the
audit, dry-run, bulk-cap, or typed-confirmation behaviour of any operation; those apply
identically regardless of channel. Core subscription connections are not counted against
the per-node call limiter.

#### Scenario: Unavailable capability is explained, not hidden

- **WHEN** a cluster's `MESSAGE_IO` is not available
- **THEN** the message views show why and the enabling configuration, with no silently missing buttons

#### Scenario: Operator calls are rate-limited per node

- **WHEN** message operations issue Jolokia calls to a node
- **THEN** each request, including every batch of a by-ids operation, is subject to the same per-node per-second ceiling as the scrape scheduler

#### Scenario: Channel choice does not weaken safety

- **WHEN** a destructive operation runs on a cluster with a Core connection
- **THEN** its dry-run, bulk cap, typed confirmation, and audit behaviour are exactly as they are over Jolokia
