## Purpose

Reconstructs request-reply message flows on a traced address from broker
notifications and sampled message browsing, so an operator can see which
requests are in flight, stuck, or unanswered, and how long answered ones take.

## Requirements

### Requirement: An operator declares which request addresses to trace

The system SHALL let an operator declare a request-reply expectation per cluster: a request
address, a set of reply address patterns, an optional deadline, a sample rate, and whether to
capture payloads. An expectation SHALL be enabled or disabled without deleting it. Every
expectation write SHALL be recorded as an audit event in the same transaction as the change.

A reply address pattern SHALL be either a literal address or a glob containing `*`, which
matches any run of characters and is anchored at both ends. An empty set SHALL mean that
replies arrive on a temporary queue named by the request, which is a valid configuration and
SHALL be distinguishable in the interface from an unfilled field.

A cluster SHALL hold at most one expectation per request address. An attempt to declare a
request address that is already traced SHALL be refused as a conflict naming that address,
and SHALL NOT write an audit event.

#### Scenario: An expectation is created

- **WHEN** an operator submits a request address and one or more reply address patterns for a cluster
- **THEN** an enabled expectation is stored for that cluster with all of those patterns, and an audit event records the creation

#### Scenario: One pattern covers reply queues that do not exist yet

- **WHEN** an operator declares the reply address pattern `orders.reply.*` and a responder later creates `orders.reply.host-7`
- **THEN** that queue is traced without the expectation being edited

#### Scenario: A pattern is anchored

- **WHEN** an expectation declares `orders.reply.*`
- **THEN** `orders.reply.host-7` matches and `legacy.orders.reply.host-7` does not

#### Scenario: An expectation can be disabled without losing its configuration

- **WHEN** an operator disables an expectation
- **THEN** tracing for that address stops and the expectation's configuration is retained for later re-enabling

#### Scenario: A duplicate request address is refused, not accepted

- **WHEN** an operator declares a request address that this cluster already traces
- **THEN** the request is refused with a conflict that names the address and says the existing expectation should be edited instead

### Requirement: Flows are reconstructed without relying on message correlation data in notifications

The system SHALL reconstruct request-reply flows using broker notifications for
lifecycle facts (bindings, consumer presence, message expiry) and non-destructive
sampled browsing of the request and reply addresses for message correlation
identity (correlation id, reply destination, message id). The system SHALL NOT
require notifications to carry correlation identity, since the broker's
notification stream does not provide it.

#### Scenario: A flow is created from an observed request

- **WHEN** a message is observed on a traced request address that has not been seen before
- **THEN** a flow is created in the awaiting-reply state with a resolved deadline

#### Scenario: A flow completes from an observed reply

- **WHEN** an observed reply message's correlation identity matches an awaiting-reply flow, by either JMS correlation convention or by the temporary reply destination
- **THEN** that flow moves to the completed state and its latency is recorded

### Requirement: A flow reaches exactly one of six terminal or in-flight states

The system SHALL represent each flow as one of: awaiting reply, completed, timed
out, orphaned, responder dropped, or orphaned reply. A flow SHALL move to timed
out or orphaned only after its deadline passes without a reply — orphaned when no
responder was ever observed for its request address, timed out otherwise. A flow
SHALL move to responder dropped when the only observed responder for its request
address disappears while the flow is still awaiting reply. A reply observed with
no matching awaiting-reply flow SHALL create a flow already in the orphaned-reply
state.

#### Scenario: An unanswered request past its deadline with no responder is orphaned

- **WHEN** a flow's deadline passes while awaiting reply and no consumer was ever observed on its request address
- **THEN** the flow moves to the orphaned state

#### Scenario: An unanswered request past its deadline with a responder times out

- **WHEN** a flow's deadline passes while awaiting reply and a consumer had been observed on its request address
- **THEN** the flow moves to the timed-out state

#### Scenario: The only responder disappearing marks the flow dropped

- **WHEN** the last observed consumer on a flow's request address closes while that flow is still awaiting reply
- **THEN** the flow moves to the responder-dropped state

#### Scenario: A reply with no matching request is recorded as orphaned

- **WHEN** a reply is observed on a traced reply address and no awaiting-reply flow matches its correlation identity
- **THEN** a flow is created directly in the orphaned-reply state

### Requirement: A flow's deadline is resolved from the message, then the expectation, then a default

The system SHALL resolve a flow's deadline from the request message's own
expiration when present, otherwise from the expectation's configured deadline,
otherwise from a system default.

#### Scenario: Message expiration wins

- **WHEN** an observed request carries its own expiration
- **THEN** the flow's deadline is set from that expiration, regardless of the expectation's configured deadline

#### Scenario: Expectation deadline is used absent message expiration

- **WHEN** an observed request carries no expiration and its expectation has a configured deadline
- **THEN** the flow's deadline is set from the expectation's configured deadline

### Requirement: Reported latency discloses its sampling coverage

The system SHALL report latency for completed flows as percentiles computed only
over observed flows, and SHALL accompany every such report with an estimate of
what fraction of actual traffic on that address was observed. The system SHALL
NOT present sampled latency without this coverage estimate.

#### Scenario: Latency is reported with coverage

- **WHEN** an operator requests latency statistics for a traced address
- **THEN** the response includes percentile latencies and the fraction of requests on that address estimated to have been observed

#### Scenario: Coverage cannot be estimated

- **WHEN** there is not enough history to estimate coverage for an address
- **THEN** the response reports latency percentiles with coverage marked as unknown, not omitted or assumed complete

### Requirement: Flow history is served through a filtered paged API

The system SHALL expose a paged read of a cluster's flows, filterable by state,
address, correlation id, and time range, and a single-flow read that includes its
observed event timeline and any captured payload.

#### Scenario: Filtered flows are returned

- **WHEN** an operator requests flows in the stuck states for an address
- **THEN** the response contains one page of matching flows with the total match count

#### Scenario: A flow's timeline is inspectable

- **WHEN** an operator requests a single flow
- **THEN** the response includes every observed event for that flow in order, and its captured payload previews when capture was enabled

### Requirement: Captured payloads are bounded and only stored when enabled

The system SHALL capture a request's and reply's payload only when the
expectation enables payload capture, and SHALL truncate a captured payload to a
configured size limit.

#### Scenario: Payload capture is off by default

- **WHEN** an expectation does not enable payload capture
- **THEN** no request or reply body is stored for its flows

#### Scenario: A captured payload is truncated

- **WHEN** payload capture is enabled and an observed body exceeds the configured limit
- **THEN** the stored payload is truncated to that limit and marked as truncated

### Requirement: The tracing screen states when tracing is not available

The frontend SHALL provide a routed request-reply view for a cluster. When the
cluster's notification capability is unavailable or it has no resolvable Core
connection, the view SHALL show why, including the enabling `broker.xml` change
when applicable, rather than an empty flows list.

#### Scenario: Unavailable capability is explained

- **WHEN** an operator opens the request-reply view for a cluster with no notification capability
- **THEN** the view shows the reason and the enabling configuration rather than an empty flows list

#### Scenario: A live cluster shows its flows and updates without a manual refresh

- **WHEN** an operator opens the request-reply view for a cluster with active tracing
- **THEN** the view lists that cluster's flows and reflects a flow's state change without the operator reloading the page

### Requirement: Request-reply configuration and flows require cluster permission

Reading request-reply expectations or flows for a cluster SHALL require read
permission at that cluster's scope. Creating, updating, or deleting an
expectation SHALL require write permission at that cluster's scope.

#### Scenario: Reading flows requires read permission

- **WHEN** a user without read permission on a cluster requests its
  request-reply flows
- **THEN** the request is rejected

#### Scenario: Creating an expectation requires write permission

- **WHEN** a user without write permission on a cluster attempts to create a
  request-reply expectation for it
- **THEN** the request is rejected

### Requirement: Reply address patterns resolve from already-collected broker state

The system SHALL resolve a reply address pattern against the addresses recorded by the
existing scrape, and SHALL NOT issue a broker call to perform the resolution. A pattern that
matches no current address SHALL NOT be an error. Resolution SHALL be capped at a bounded
number of addresses per expectation, and reaching that cap SHALL be reported to the operator
rather than silently truncated.

#### Scenario: A pattern costs no broker call

- **WHEN** an expectation with a reply address pattern is sampled
- **THEN** the pattern is resolved from already-collected state and no additional request is made to the broker to resolve it

#### Scenario: A pattern matching nothing yet is accepted

- **WHEN** an operator declares a reply address pattern that matches no address on the broker
- **THEN** the expectation is stored and tracing begins once a matching address appears

#### Scenario: An over-broad pattern is capped and reported

- **WHEN** a pattern resolves to more addresses than the cap allows
- **THEN** sampling covers the capped set and the operator is told the pattern is too broad

### Requirement: A reply on any resolved reply address joins its flow

The system SHALL join a reply to an awaiting-reply flow when the reply is observed on any
address that resolves for that flow's expectation and its correlation identity matches,
regardless of which of those addresses it arrived on. A flow whose expectation resolves to
more than one reply address SHALL be created with no reply destination and SHALL record the
address the joining reply arrived on. A flow whose expectation resolves to exactly one
literal address SHALL be created already naming it.

#### Scenario: A reply on one of several resolved reply queues completes the flow

- **WHEN** a request is observed on a traced address whose expectation resolves to three reply addresses, and a reply with matching correlation identity is later observed on the second of them
- **THEN** that flow moves to the completed state, its latency is recorded, and its reply destination names the second reply address

#### Scenario: A single resolved reply address is known in advance

- **WHEN** a request is observed on a traced address whose expectation resolves to exactly one literal reply address
- **THEN** the flow is created already naming that address as its reply destination

### Requirement: A delivery on a resolved reply address is observed as a reply

The system SHALL treat a message delivery notification on an address that resolves for some
expectation as a reply observation, in addition to the existing temporary-queue case. Because
such a notification carries no correlation identity, it SHALL complete a shared-queue flow
only where correlation identity has already been observed by sampling, and SHALL otherwise be
recorded as an orphaned reply.

#### Scenario: A reply drained faster than the sampling interval is still seen

- **WHEN** a reply is consumed from a resolved shared reply queue between two sampling ticks, and its request's correlation identity was already sampled
- **THEN** the flow completes rather than timing out

### Requirement: Every serving node of the cluster is sampled

The system SHALL sample the traced request address and every resolved reply address on each
node of the cluster that is active, is not in error, and has a Core endpoint — not only the
first such node. Sampling SHALL remain a bounded first-page browse per address per node, so
the cost grows with the number of nodes and never with queue depth.

#### Scenario: Traffic on a node other than the first is still traced

- **WHEN** a request-reply exchange happens entirely on the second of three active nodes
- **THEN** its flow is reconstructed, because that node was sampled too

#### Scenario: One unreachable node does not stop the others

- **WHEN** sampling one node fails
- **THEN** the remaining nodes are still sampled on that tick

### Requirement: A failing sample is reported, not swallowed

The system SHALL report a sampling failure for an expectation at warning level, naming the
expectation and the node, on its first occurrence and at a bounded rate thereafter. A
sampling failure SHALL NOT propagate out of the sampling tick.

#### Scenario: A misconfigured address is visible in the log

- **WHEN** sampling an expectation fails repeatedly because its address does not exist on the broker
- **THEN** the failure is logged at warning level naming the expectation and the node, at a bounded rate rather than on every tick
