## Purpose

Reconstructs request-reply message flows on a traced address from broker
notifications and sampled message browsing, so an operator can see which
requests are in flight, stuck, or unanswered, and how long answered ones take.

## Requirements

### Requirement: An operator declares which request addresses to trace

The system SHALL let an operator declare a request-reply expectation per
cluster: a request address, an optional reply address (for the shared-reply-queue
pattern), an optional deadline, a sample rate, and whether to capture payloads.
An expectation SHALL be enabled or disabled without deleting it. Every
expectation write SHALL be recorded as an audit event in the same transaction as
the change.

#### Scenario: An expectation is created

- **WHEN** an operator submits a request address and reply address for a cluster
- **THEN** an enabled expectation is stored for that cluster and an audit event records the creation

#### Scenario: An expectation can be disabled without losing its configuration

- **WHEN** an operator disables an expectation
- **THEN** tracing for that address stops and the expectation's configuration is retained for later re-enabling

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
