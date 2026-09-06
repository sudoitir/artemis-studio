## MODIFIED Requirements

### Requirement: An operator declares which request addresses to trace

The system SHALL let an operator declare a request-reply expectation per cluster: a request
address, a set of reply addresses (empty when replies arrive on a temporary queue named by
the request), an optional deadline, a sample rate, and whether to capture payloads. An
expectation SHALL be enabled or disabled without deleting it. Every expectation write SHALL
be recorded as an audit event in the same transaction as the change.

A cluster SHALL hold at most one expectation per request address. An attempt to declare a
request address that is already traced SHALL be refused as a conflict naming that address,
and SHALL NOT write an audit event.

#### Scenario: An expectation is created

- **WHEN** an operator submits a request address and one or more reply addresses for a cluster
- **THEN** an enabled expectation is stored for that cluster with all of those reply addresses, and an audit event records the creation

#### Scenario: Several shared reply queues are declared for one request address

- **WHEN** an operator declares a request address whose responders answer on one shared reply queue per application instance
- **THEN** every one of those reply queues is stored on the single expectation for that request address

#### Scenario: An expectation can be disabled without losing its configuration

- **WHEN** an operator disables an expectation
- **THEN** tracing for that address stops and the expectation's configuration is retained for later re-enabling

#### Scenario: A duplicate request address is refused, not accepted

- **WHEN** an operator declares a request address that this cluster already traces
- **THEN** the request is refused with a conflict that names the address and says the existing expectation should be edited instead

## ADDED Requirements

### Requirement: A reply on any declared reply address joins its flow

The system SHALL join a reply to an awaiting-reply flow when the reply is observed on any of
the reply addresses declared on that flow's expectation and its correlation identity matches,
regardless of which of those addresses it arrived on. A flow whose expectation declares more
than one reply address SHALL be created with no reply destination, and SHALL record the
address the joining reply actually arrived on.

#### Scenario: A reply on the second of three declared reply queues completes the flow

- **WHEN** a request is observed on a traced address whose expectation declares three reply addresses, and a reply with matching correlation identity is later observed on the second of them
- **THEN** that flow moves to the completed state, its latency is recorded, and its reply destination names the second reply address

#### Scenario: A single declared reply address is still known in advance

- **WHEN** a request is observed on a traced address whose expectation declares exactly one reply address
- **THEN** the flow is created already naming that address as its reply destination

### Requirement: Every serving node of the cluster is sampled

The system SHALL sample the traced request address and every declared reply address on each
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
