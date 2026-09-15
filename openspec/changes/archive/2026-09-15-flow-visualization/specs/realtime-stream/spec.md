## MODIFIED Requirements

### Requirement: One multiplexed event stream per cluster

The system SHALL expose a single streaming endpoint that a client opens with a cluster identifier and a set of topics, and that delivers named events for the subscribed topics only. The recognised topics SHALL be exactly those declared by the installation's enabled features. With every feature enabled, they SHALL include topology, health, queues, events, consumers, sessions, connections, request-reply, alerts, configuration and flow. A topic name the endpoint does not recognise, including a topic of a disabled feature, SHALL be ignored rather than rejected.

A topic carries state that is the same for every subscriber of a cluster. Delivery specific to one client's request, where the payload depends on parameters that client supplied and no other subscriber shares, SHALL NOT be added as a topic on this stream. It SHALL be served by its own stream, scoped to that request, which ends when that client disconnects. Such a stream SHALL apply the same permission check, the same heartbeat, and the same subscriber-release behaviour as this one.

#### Scenario: Client subscribes to a subset of topics

- **WHEN** a client opens the stream for a cluster requesting only the topology topic
- **THEN** it receives topology events for that cluster and no queues or health events

#### Scenario: Events are scoped to the cluster

- **WHEN** state changes for one cluster
- **THEN** only clients subscribed to that cluster receive the event

#### Scenario: Client subscribes to the events topic

- **WHEN** a client opens the stream requesting the events topic
- **THEN** it receives broker-event payloads for that cluster and no topic it did not request

#### Scenario: Client subscribes to the alerts topic

- **WHEN** a client opens the stream for a cluster requesting the alerts topic
- **THEN** it receives an alerts signal event whenever that cluster's alert firing state changes, and no topic it did not request

#### Scenario: Client subscribes to the flow topic

- **WHEN** a client opens the stream for a cluster requesting the flow topic
- **THEN** the client receives a flow signal whenever a sampling sweep changes that cluster's flow state

#### Scenario: A disabled feature's topic is ignored

- **WHEN** a client requests the alerts topic and the topology topic on an installation with alerting disabled
- **THEN** the stream opens, delivers topology events, and never delivers an alerts event

#### Scenario: Per-request delivery gets its own stream

- **WHEN** a client needs delivery whose payload depends on parameters it alone supplied
- **THEN** it is served by a separate stream scoped to that request rather than by a topic on the cluster stream, and that stream ends when the client disconnects

### Requirement: Events are change signals derived from polling or push

Signal-topic events (topology, health, queues, consumers, sessions, connections,
alerts, flow) SHALL each carry their topic, the cluster they concern, and a timestamp, and
SHALL signal that the topic's data has changed so the client can refetch it. A
signal-topic event SHALL be emitted only on an actual change: for a scrape-path
topic, only when a scrape tick changed that topic's persisted state; for a
push-path topic, only when a received notification implies that topic is stale;
for the alerts topic, only when an alert evaluation tick changes a firing state for
that cluster; for the flow topic, only when a client-activity sweep changed that
cluster's persisted flow state.

The events topic is the exception: it SHALL carry the full broker-event payload
and a monotonic event id, not a bare signal, and its events SHALL originate from
the push path.

#### Scenario: No change, no event

- **WHEN** a scrape tick produces the same topology and health as the previous tick
- **THEN** no topology or health event is emitted

#### Scenario: A queue depth change emits a queues event

- **WHEN** a scrape tick changes a queue's cached counters
- **THEN** a queues event for that cluster is emitted and subscribed clients refetch

#### Scenario: A notification carries data on the events topic

- **WHEN** a broker notification is received and persisted for a cluster
- **THEN** an events-topic message is delivered with the broker-event payload and a monotonic event id

#### Scenario: An alert transition emits an alerts signal

- **WHEN** an alert evaluation tick for a cluster causes a rule to start firing or
  resolve
- **THEN** an alerts signal event for that cluster is emitted and subscribed clients
  refetch

#### Scenario: An unchanged alert evaluation emits nothing

- **WHEN** an alert evaluation tick for a cluster produces no new firing or
  resolution
- **THEN** no alerts event is emitted for that tick

#### Scenario: A flow sweep that changed nothing emits nothing

- **WHEN** a client-activity sweep for a cluster persists the same flow state as the previous sweep
- **THEN** no flow event is emitted for that sweep
