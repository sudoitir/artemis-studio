# realtime-stream Specification

## Purpose
Defines the single server-to-browser event stream Artemis Studio uses to tell
the UI when polled cluster state has changed, the topics it multiplexes, and how
the client behaves when the stream is unavailable.

## Requirements

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

### Requirement: Events are change signals derived from polling or push

Signal-topic events (topology, health, queues, consumers, sessions, connections,
alerts) SHALL each carry their topic, the cluster they concern, and a timestamp, and
SHALL signal that the topic's data has changed so the client can refetch it. A
signal-topic event SHALL be emitted only on an actual change: for a scrape-path
topic, only when a scrape tick changed that topic's persisted state; for a
push-path topic, only when a received notification implies that topic is stale;
for the alerts topic, only when an alert evaluation tick changes a firing state for
that cluster.

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

### Requirement: The stream survives idle periods and proxies

The system SHALL send a periodic keep-alive on an otherwise idle stream as a
**named event a client can observe**, and SHALL instruct intermediaries not to
buffer the response.

The keep-alive SHALL carry no data a client is required to interpret, so that a
client that does not subscribe to it is unaffected.

#### Scenario: Idle stream stays open

- **WHEN** no events occur for longer than the keep-alive interval
- **THEN** a keep-alive event is sent and the connection remains open

#### Scenario: The keep-alive is observable by the client

- **WHEN** a client subscribes to the keep-alive event on an otherwise idle stream
- **THEN** it receives a frame at the keep-alive interval, so that continued
  silence is distinguishable from an idle connection

### Requirement: A subscriber that goes away is released

The system SHALL deregister a subscriber and free its resources when its stream
completes, times out, or errors.

#### Scenario: Client closes the tab

- **WHEN** a client's stream connection closes
- **THEN** the server removes that subscriber and stops attempting to send to it

### Requirement: Opening the stream requires cluster read permission

The system SHALL require authentication to open the event stream, and SHALL
require the caller to hold a read permission at the requested cluster's scope,
rejecting the subscription otherwise.

#### Scenario: Unauthenticated stream request is rejected

- **WHEN** a client with no authenticated session or token opens the stream
- **THEN** the connection is rejected

#### Scenario: Stream request for an ungranted cluster is rejected

- **WHEN** an authenticated client opens the stream for a cluster it holds no
  read grant on
- **THEN** the connection is rejected

### Requirement: The client reconnects indefinitely and reports its state

When the stream fails, the client SHALL retry indefinitely with capped exponential
backoff and randomised delay, rather than stopping after a bounded number of
attempts. It SHALL report its connection state — connecting, connected,
reconnecting, or unreachable — to the interface, and the affected views SHALL
continue updating on their periodic refetch while it is not connected.

The client SHALL treat prolonged silence on an open stream as a failure: if no
frame of any kind arrives within a window longer than the keep-alive interval, it
SHALL close the connection and reconnect.

#### Scenario: Repeated failures keep retrying

- **WHEN** the client's stream connection fails several times in a row
- **THEN** it continues to retry with an increasing, randomised, capped delay
  rather than stopping

#### Scenario: Recovery without a reload

- **WHEN** the server becomes reachable again after a stream outage
- **THEN** the client reconnects on its own and reports that it is connected again

#### Scenario: A silently dropped connection is detected

- **WHEN** an intermediary drops the connection without the client observing an
  error, and no frame arrives within the silence window
- **THEN** the client closes the connection and reconnects

#### Scenario: The interface can report the stream state

- **WHEN** the stream is not connected
- **THEN** the interface can report that updates are arriving by periodic refetch
  rather than live
