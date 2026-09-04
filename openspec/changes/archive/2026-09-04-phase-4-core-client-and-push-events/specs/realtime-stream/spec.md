## RENAMED Requirements

- FROM: `### Requirement: Events are change signals derived from polling`
- TO: `### Requirement: Events are change signals derived from polling or push`

## MODIFIED Requirements

### Requirement: One multiplexed event stream per cluster

The system SHALL expose a single streaming endpoint that a client opens with a
cluster identifier and a set of topics, and that delivers named events for the
subscribed topics only. The supported topics SHALL include topology, health,
queues, events, consumers, sessions, and connections. A topic name the endpoint
does not recognise SHALL be ignored rather than rejected.

#### Scenario: Client subscribes to a subset of topics

- **WHEN** a client opens the stream for a cluster requesting only the topology topic
- **THEN** it receives topology events for that cluster and no queues or health events

#### Scenario: Events are scoped to the cluster

- **WHEN** state changes for one cluster
- **THEN** only clients subscribed to that cluster receive the event

#### Scenario: Client subscribes to the events topic

- **WHEN** a client opens the stream requesting the events topic
- **THEN** it receives broker-event payloads for that cluster and no topic it did not request

### Requirement: Events are change signals derived from polling or push

Signal-topic events (topology, health, queues, consumers, sessions, connections)
SHALL each carry their topic, the cluster they concern, and a timestamp, and
SHALL signal that the topic's data has changed so the client can refetch it. A
signal-topic event SHALL be emitted only on an actual change: for a scrape-path
topic, only when a scrape tick changed that topic's persisted state; for a
push-path topic, only when a received notification implies that topic is stale.

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

## ADDED Requirements

### Requirement: A reconnecting client replays missed broker events

When a client reopens the stream and presents the id of the last events-topic
message it received, the system SHALL redeliver the persisted broker events for
that cluster with a higher id before resuming live delivery. The replay SHALL be
bounded: a client that has been gone longer than the retained history or the
replay cap receives at most the capped number of most-recent missed events.

#### Scenario: Short gap is fully replayed

- **WHEN** a client reconnects after a brief disconnect presenting its last event id
- **THEN** it receives every persisted broker event newer than that id, in order, before live events resume

#### Scenario: Long gap is capped

- **WHEN** a client reconnects presenting an event id older than the replay cap allows
- **THEN** it receives at most the capped number of most-recent events rather than the entire backlog

### Requirement: Notification-driven signal emissions are coalesced per cluster

Because the consumers, sessions, connections, and queues views are served by
live per-node broker reads, the system SHALL emit at most one signal per such
topic per cluster per coalescing window, however many notifications in that
window imply the topic is stale.

#### Scenario: A burst produces one refetch

- **WHEN** many consumer notifications for a cluster arrive within one coalescing window
- **THEN** at most one consumers signal is emitted for that cluster in that window
