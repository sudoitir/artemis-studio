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
instead, and the response SHALL state that it was.

A filter expression the broker rejects SHALL be reported as invalid without
repeating the expression's text, because a filter can carry sensitive literals.

#### Scenario: Browse returns a page

- **WHEN** an operator browses a queue that holds more messages than the page size
- **THEN** the response contains one page of message summaries and the queue's total message count

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
