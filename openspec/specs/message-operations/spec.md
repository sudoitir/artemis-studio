# message-operations Specification

## Purpose

Defines what an operator can do to the messages on a queue from Artemis Studio
over the Jolokia management channel — browse them with full headers and
properties, send one, and move / retry / delete / expire / purge them by ids or
by filter — and the safety contract around every mutation: a dry run that
reports the blast radius without acting, a server-enforced bulk cap, and an
audit record written in the command's own transaction.

## Requirements

### Requirement: A queue's messages can be browsed with full headers and properties

The system SHALL expose a paged read of the messages on one queue of one node,
accepting an optional Artemis filter expression. Each returned message SHALL
carry its message id, type, durability, priority, timestamp, expiration, size,
and the count of application properties. A single-message read SHALL additionally
return the full header set and the string, integer, long, and boolean property
maps, and the message body as text. The browse SHALL cost exactly one batched
Jolokia POST per call (the `browse` exec and the `MessageCount` read in one
array).

#### Scenario: Browse returns a page

- **WHEN** an operator browses a queue that holds more messages than the page size
- **THEN** the response contains one page of message summaries and the queue's
  total message count

#### Scenario: Browse honours a filter

- **WHEN** an operator browses with an Artemis filter expression
- **THEN** only messages matching the filter are returned

#### Scenario: Single POST per browse

- **WHEN** a browse is served
- **THEN** exactly one Jolokia POST is issued to the target node for that browse

#### Scenario: Node is explicit or defaulted to the live node

- **WHEN** a browse request omits the node
- **THEN** the message-holding live node of the logical node serving that queue
  is used, and the response states which node answered

### Requirement: Truncated message content is detected and disclosed

Because the Jolokia management channel truncates returned message data at the
broker's `management-message-attribute-size-limit`, every browsed message SHALL
carry a boolean indicating whether its body or properties were truncated, and
the single-message read SHALL carry the effective limit in bytes. The frontend
SHALL show an explicit notice, naming the limit and the `broker.xml`
`address-setting` that raises it, whenever truncated content is displayed, and
SHALL state that faithful binary message I/O arrives with the Core client in a
later phase.

#### Scenario: A large body is flagged

- **WHEN** a browsed message's body exceeds the broker's attribute size limit
- **THEN** its truncation flag is true and the detail view shows the limit and
  the enabling `broker.xml` snippet

#### Scenario: Small messages are not flagged

- **WHEN** every returned message is within the limit
- **THEN** no truncation flag is set and no truncation notice is shown

### Requirement: A message can be sent to a queue's address

The system SHALL expose sending a message to the address of a named queue, with
a caller-supplied body text, message type, durability flag, and application
properties. Over the Jolokia channel the body SHALL be carried as text; the API
and UI SHALL state that binary bodies are not faithful in this phase.

#### Scenario: Send places a message

- **WHEN** an operator sends a message with a body and properties
- **THEN** the message appears on the target queue with those properties and a
  new message id is reported

#### Scenario: Invalid send is rejected

- **WHEN** a send request omits a required field
- **THEN** it is rejected with a validation error and no message is sent

### Requirement: Messages can be moved, retried, deleted, or expired by ids or by filter

The system SHALL expose move, retry, delete, and expire operations on the
messages of one queue, targeted either by an explicit list of message ids or by
an Artemis filter expression. Move SHALL take a target queue. The affected-count
result SHALL be the broker's own operation result where the broker returns one,
and otherwise the number of ids acted on.

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

### Requirement: A queue can be purged

The system SHALL expose removing every message from one queue of one node. The
frontend SHALL require the queue name to be typed to confirm before a purge is
sent.

#### Scenario: Purge empties the queue

- **WHEN** an operator confirms a purge
- **THEN** every message is removed and the removed count is reported

#### Scenario: Purge needs typed confirmation in the UI

- **WHEN** an operator initiates a purge from the frontend
- **THEN** the action is disabled until the exact queue name is typed

### Requirement: Every mutation supports a dry run that does not act

Every message mutation — send, move, retry, delete, expire, purge — SHALL accept
a dry-run flag. In dry-run mode the system SHALL return an affected-count
estimate — `countMessages(filter)` for a by-filter target, the id count for a
by-id target, the queue's message count for a purge, and one for a send — and
SHALL issue no Jolokia request that changes broker state. The response SHALL
mark the count as a point-in-time estimate.

#### Scenario: Dry run reports a count without acting

- **WHEN** an operator runs a by-filter delete with the dry-run flag
- **THEN** the estimated match count is returned and no message is removed

#### Scenario: Dry run issues no mutating call

- **WHEN** any mutation is invoked in dry-run mode
- **THEN** no state-changing Jolokia operation is sent to the broker

### Requirement: Bulk mutations are capped and the cap is enforced server-side

The system SHALL refuse any mutation whose dry-run affected count exceeds the
configured bulk cap, returning an error that names the count and the cap, unless
the request carries an explicit override flag. The frontend SHALL only send the
override flag after showing the dry-run preview and requiring the queue name to
be typed.

#### Scenario: Over the cap without override is refused

- **WHEN** a by-filter mutation would affect more messages than the cap and no
  override is set
- **THEN** the request is rejected with an error naming the affected count and
  the cap, and nothing is changed

#### Scenario: Override proceeds after confirmation

- **WHEN** the same mutation is retried with the override flag
- **THEN** it proceeds

#### Scenario: Under the cap proceeds normally

- **WHEN** a mutation's affected count is at or below the cap
- **THEN** no override is required

### Requirement: Every mutation writes an audit event in its own transaction

Every message mutation SHALL write an `audit_event` row in the same database
transaction as the command: created with a pending outcome before the broker
call, updated to success with the affected count or to failure with the error
after. A dry run SHALL also be audited, marked as a dry run with a success
outcome. A broker failure SHALL still leave a committed failure row and SHALL be
returned to the caller as a structured problem response.

#### Scenario: Successful mutation is audited

- **WHEN** a move succeeds
- **THEN** a committed `audit_event` records the action, target queue, actor,
  affected count, and a success outcome

#### Scenario: Failed mutation is audited

- **WHEN** the broker rejects a delete
- **THEN** a committed `audit_event` records the failure and its error, and the
  caller receives a structured problem response

#### Scenario: Dry run is audited

- **WHEN** a mutation runs in dry-run mode
- **THEN** a committed `audit_event` marked as a dry run records the estimated
  count

### Requirement: Message operations require message-IO capability and are rate-limited

The system SHALL gate the message views on the connection's `MESSAGE_IO`
capability, rendering the capability reason and `broker.xml` guidance rather than
hiding the controls when it is unavailable. Every broker call a message
operation makes SHALL pass through the per-node management-call rate limiter.

#### Scenario: Unavailable capability is explained, not hidden

- **WHEN** a cluster's `MESSAGE_IO` is not available
- **THEN** the message views show why and the enabling configuration, with no
  silently missing buttons

#### Scenario: Operator calls are rate-limited per node

- **WHEN** message operations issue broker calls to a node
- **THEN** those calls are subject to the same per-node per-second ceiling as the
  scrape scheduler
