## MODIFIED Requirements

### Requirement: A queue's messages can be browsed with full headers and properties

The system SHALL expose a paged read of the messages on one queue of one node,
accepting an optional Artemis filter expression. Each returned message SHALL
carry its message id, type, durability, priority, timestamp, expiration, size,
the count of application properties, its correlation id, and its reply-to
destination when the message has one. A single-message read SHALL additionally
return the full header set and the string, integer, long, and boolean property
maps, and the message body.

The browse SHALL be served over the Core client when the cluster has an
available Core connection, and over Jolokia otherwise. Over the Core client the
body SHALL be returned faithfully — text as text, binary as bytes with an
encoding indicator — and application properties SHALL keep their real types.
Over Jolokia the browse SHALL cost exactly one batched Jolokia POST per call
(the `browse` exec and the `MessageCount` read in one array). Every browse and
single-message response SHALL state which channel served it.

Over the Core client, because a queue browser has no server-side offset, a
requested page beyond a bounded browse depth SHALL be served over Jolokia
instead, and the response SHALL state that it was.

#### Scenario: Browse returns a page

- **WHEN** an operator browses a queue that holds more messages than the page size
- **THEN** the response contains one page of message summaries and the queue's total message count

#### Scenario: Browse honours a filter

- **WHEN** an operator browses with an Artemis filter expression
- **THEN** only messages matching the filter are returned

#### Scenario: Core channel returns a faithful body

- **WHEN** a queue with an available Core connection holds a message with a binary body
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
