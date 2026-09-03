# realtime-stream Specification

## Purpose
Defines the single server-to-browser event stream Artemis Studio uses to tell
the UI when polled cluster state has changed, the topics it multiplexes, and how
the client behaves when the stream is unavailable.

## Requirements

### Requirement: One multiplexed event stream per cluster

The system SHALL expose a single streaming endpoint that a client opens with a
cluster identifier and a set of topics, and that delivers named events for the
subscribed topics only. The supported topics SHALL include topology, health, and
queues.

#### Scenario: Client subscribes to a subset of topics

- **WHEN** a client opens the stream for a cluster requesting only the topology topic
- **THEN** it receives topology events for that cluster and no queues or health events

#### Scenario: Events are scoped to the cluster

- **WHEN** state changes for one cluster
- **THEN** only clients subscribed to that cluster receive the event

### Requirement: Events are change signals derived from polling

Each event SHALL carry its topic, the cluster it concerns, and a timestamp, and
SHALL signal that the topic's data has changed so the client can refetch it. The
system SHALL emit an event only when a scrape tick actually changed that topic's
persisted state, not on every tick. In this phase events SHALL be derived solely
from the scrape path.

#### Scenario: No change, no event

- **WHEN** a scrape tick produces the same topology and health as the previous tick
- **THEN** no topology or health event is emitted

#### Scenario: A queue depth change emits a queues event

- **WHEN** a scrape tick changes a queue's cached counters
- **THEN** a queues event for that cluster is emitted and subscribed clients refetch

### Requirement: The stream survives idle periods and proxies

The system SHALL send a periodic keep-alive comment on an otherwise idle stream
and SHALL instruct intermediaries not to buffer the response.

#### Scenario: Idle stream stays open

- **WHEN** no events occur for longer than the keep-alive interval
- **THEN** a keep-alive comment is sent and the connection remains open

### Requirement: A subscriber that goes away is released

The system SHALL deregister a subscriber and free its resources when its stream
completes, times out, or errors.

#### Scenario: Client closes the tab

- **WHEN** a client's stream connection closes
- **THEN** the server removes that subscriber and stops attempting to send to it

### Requirement: The client falls back to polling

When the stream fails, the client SHALL retry a bounded number of times and then
stop, relying on periodic refetch of the affected data until the view is
reopened.

#### Scenario: Two consecutive stream failures

- **WHEN** the client's stream connection fails twice in a row
- **THEN** the client stops reconnecting and the affected views continue updating
  on their normal refetch interval
