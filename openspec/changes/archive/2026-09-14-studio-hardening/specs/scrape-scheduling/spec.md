## MODIFIED Requirements

### Requirement: Management calls are rate-limited per node

The system SHALL enforce a configurable ceiling on management requests per second to each
individual node. The ceiling SHALL be applied to every HTTP request Studio issues to that
node, whatever the origin:
- a scrape tick;
- an operator-initiated operation such as a message browse or mutation;
- cluster registration and rediscovery;
- a capability probe;
- a multi-step command;
- capture installation or removal.

A single request that carries many operations SHALL count against the ceiling in proportion
to the operations it carries, so batching can never be used to exceed it. The default
ceiling SHALL be conservative so that Studio is never the reason a broker is overloaded.

#### Scenario: Bursts are shaped, not dropped

- **WHEN** Studio would exceed a node's per-second ceiling
- **THEN** the excess calls wait until capacity is available rather than failing

#### Scenario: One slow node does not stall the others

- **WHEN** one node is rate-limited or slow to respond
- **THEN** other nodes and other clusters continue to be scraped on schedule

#### Scenario: Operator-initiated calls share the ceiling

- **WHEN** an operator browses or mutates messages on a node while that node is
  being scraped
- **THEN** both the scrape and the operator call are counted against the same
  per-node per-second ceiling

#### Scenario: A multi-request command counts every request

- **WHEN** a command issues several management requests to one node, such as installing a capture tap
- **THEN** each of those requests is counted against that node's ceiling, not only the first

#### Scenario: Registration and capability probes are counted

- **WHEN** a cluster is registered, rediscovered, or has its capabilities probed
- **THEN** every management request that issues to a node is counted against that node's ceiling

#### Scenario: A large batch cannot exceed the ceiling

- **WHEN** an operation carries more operations in one request than the ceiling allows per second
- **THEN** it is spread across as many seconds as the ceiling requires
