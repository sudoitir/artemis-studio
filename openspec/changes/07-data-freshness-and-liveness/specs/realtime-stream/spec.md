## MODIFIED Requirements

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
