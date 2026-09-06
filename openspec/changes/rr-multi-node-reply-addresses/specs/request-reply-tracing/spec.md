## MODIFIED Requirements

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

## ADDED Requirements

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
