## ADDED Requirements

### Requirement: Capture is an observation source for correlation

Where a traced request or reply address is covered by an active capture subscription,
correlation SHALL observe messages from that capture rather than by sampling the address,
and SHALL record that it did so.

A message routed to a captured address and consumed before any sample could see it SHALL
still join its flow. Sampling SHALL remain in use for addresses that are not captured, so
tracing is not made dependent on capture.

The two sources SHALL NOT be double-counted: a message observed both by capture and by a
sample SHALL produce one observation, not two.

#### Scenario: An immediately consumed reply joins its flow

- **WHEN** a reply is routed to a captured reply address and consumed before any sample could observe it
- **THEN** it is correlated to its request and the flow reaches its completed state

#### Scenario: An uncaptured address is still sampled

- **WHEN** a traced address has no capture subscription
- **THEN** it continues to be sampled and tracing continues to produce flows for it

#### Scenario: The same message is observed once

- **WHEN** a message on a captured address is also seen by a sample
- **THEN** one observation is recorded for it

### Requirement: Tracing states what capture cannot cover

A reply address that is created by the client at request time — a temporary queue — cannot
be captured, because a tap must be installed on an address before the message is routed to
it and such an address does not exist until the client creates it.

The system SHALL state, where an expectation's replies arrive on temporary queues, that
those replies are observed from broker notifications rather than from capture, and that the
completeness capture gives elsewhere does not extend to them.

The system SHALL NOT report an expectation as fully captured when part of its flow is not.

#### Scenario: A temporary reply address is disclosed as uncaptured

- **WHEN** an expectation whose replies arrive on temporary queues is viewed while its request address is captured
- **THEN** the interface states that the reply side is observed from notifications and is not captured

#### Scenario: Partial capture is not reported as full capture

- **WHEN** an expectation's request address is captured and its reply address is not
- **THEN** the expectation is not presented as fully captured
