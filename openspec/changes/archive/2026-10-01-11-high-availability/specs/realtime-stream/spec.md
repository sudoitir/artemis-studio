## ADDED Requirements

### Requirement: A client sees the same events whichever replica serves it
When several Studio replicas share one database, every stream frame produced on one replica SHALL reach
the matching subscribers on every replica within one second, and SHALL be sent only after the change
that caused it has committed. A replica that loses its link to the other replicas SHALL tell its stream
clients to refetch once the link is back, so no view stays stale.

#### Scenario: Event on another replica
- **WHEN** a change is detected by replica A
- **THEN** clients on replica B receive its frame within one second

#### Scenario: Nothing is announced before it is stored
- **WHEN** broker events are written to the database
- **THEN** no client receives them before the write has committed

#### Scenario: The link between replicas drops
- **WHEN** a replica reconnects to the other replicas after losing its link
- **THEN** its stream clients are told to refetch their views

### Requirement: Shutdown drains event streams
On shutdown a replica SHALL stop accepting new streams, SHALL tell each of its stream clients to
reconnect, and only then SHALL close them. A client told to reconnect SHALL do so at once, without
backoff, and SHALL present its last event id.

#### Scenario: Rolling restart
- **WHEN** a replica is stopped while clients are connected to it
- **THEN** its clients reconnect to another replica without losing events

#### Scenario: New stream while draining
- **WHEN** a client opens a stream on a replica that is draining
- **THEN** the request is refused as unavailable and the client retries elsewhere

## MODIFIED Requirements

### Requirement: A reconnecting client replays missed broker events

When a client reopens the stream and presents the id of the last events-topic
message it received, the system SHALL redeliver the persisted broker events for
that cluster with a higher id before resuming live delivery, whichever replica
serves the new stream. The client SHALL present that id itself on every new
connection, not rely on the browser to do it. Live events that arrive during the
replay SHALL be held and delivered after it, without repeating a replayed id.
The replay SHALL be bounded: a client that has been gone longer than the retained
history or the replay cap receives at most the capped number of most-recent
missed events, followed by a signal to refetch its views.

#### Scenario: Short gap is fully replayed

- **WHEN** a client reconnects after a brief disconnect presenting its last event id
- **THEN** it receives every persisted broker event newer than that id, in order, before live events resume

#### Scenario: Reconnect elsewhere

- **WHEN** a client reconnects to another replica presenting its last event id
- **THEN** it replays what it missed, in order, with no gap and no duplicate

#### Scenario: Long gap is capped

- **WHEN** a client reconnects presenting an event id older than the replay cap allows
- **THEN** it receives at most the capped number of most-recent events rather than the entire backlog, and is told to refetch its views

### Requirement: The client reconnects indefinitely and reports its state

When the stream fails, the client SHALL retry indefinitely with capped exponential
backoff and randomised delay, rather than stopping after a bounded number of
attempts. It SHALL report its connection state — connecting, connected,
reconnecting, or unreachable — to the interface, and the affected views SHALL
continue updating on their periodic refetch while it is not connected. After a
reconnect that followed a failure, the client SHALL refetch the views the stream
feeds, since change signals sent while it was away are not replayed.

The client SHALL treat prolonged silence on an open stream as a failure: if no
frame of any kind arrives within a window longer than the keep-alive interval, it
SHALL close the connection and reconnect.

#### Scenario: Repeated failures keep retrying

- **WHEN** the client's stream connection fails several times in a row
- **THEN** it continues to retry with an increasing, randomised, capped delay
  rather than stopping

#### Scenario: Recovery without a reload

- **WHEN** the server becomes reachable again after a stream outage
- **THEN** the client reconnects on its own, reports that it is connected again, and refetches its views

#### Scenario: A silently dropped connection is detected

- **WHEN** an intermediary drops the connection without the client observing an
  error, and no frame arrives within the silence window
- **THEN** the client closes the connection and reconnects

#### Scenario: The interface can report the stream state

- **WHEN** the stream is not connected
- **THEN** the interface can report that updates are arriving by periodic refetch
  rather than live
