## MODIFIED Requirements

### Requirement: One multiplexed event stream per cluster

The system SHALL expose a single streaming endpoint that a client opens with a
cluster identifier and a set of topics, and that delivers named events for the
subscribed topics only. The supported topics SHALL include topology, health,
queues, events, consumers, sessions, connections, and alerts. A topic name the
endpoint does not recognise SHALL be ignored rather than rejected.

A topic carries state that is the same for every subscriber of a cluster. Delivery
that is specific to one client's request — where the payload depends on parameters
that client supplied and no other subscriber shares — SHALL NOT be added as a topic
on this stream. It SHALL be served by its own stream, scoped to that request, which
ends when that client disconnects. Such a stream SHALL apply the same permission
check, the same heartbeat, and the same subscriber-release behaviour as this one.

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
- **THEN** it receives an alerts signal event whenever that cluster's alert firing
  state changes, and no topic it did not request

#### Scenario: Per-request delivery gets its own stream

- **WHEN** a client needs delivery whose payload depends on parameters it alone supplied
- **THEN** it is served by a separate stream scoped to that request rather than by a topic on the cluster stream, and that stream ends when the client disconnects
