## MODIFIED Requirements

### Requirement: A subscriber that goes away is released

The system SHALL deregister a subscriber and free its resources when its stream
completes, times out, or errors. The thread that drains a subscriber's buffer SHALL
end in every case, including when the subscriber is dropped for lag.

#### Scenario: Client closes the tab

- **WHEN** a client's stream connection closes
- **THEN** the server removes that subscriber and stops attempting to send to it

#### Scenario: The drain thread always ends

- **WHEN** a subscriber completes, errors, times out or is dropped for lag
- **THEN** the thread that drained its buffer has ended and no thread remains for it

## ADDED Requirements

### Requirement: A slow event-stream client cannot grow Studio's memory
Studio SHALL bound the buffered events per subscriber to 1,000 frames and 1 MiB. When a subscriber falls behind, its pending change signals SHALL be coalesced by topic and the resource they are about, so that only the latest signal for each remains. A subscriber that overflows its bound three times within 60 seconds, or whose write stays blocked for more than 30 seconds, SHALL be sent a `reconnect` instruction and closed. The buffer that holds events during a replay SHALL also be bounded, and on overflow the subscriber SHALL be told to resync with a full refresh. Each frame dropped SHALL be counted in a metric.

#### Scenario: A client stops reading
- **WHEN** a subscriber reads far slower than events arrive
- **THEN** its buffer stays within 1,000 frames and 1 MiB and memory use does not grow with time

#### Scenario: Signals are coalesced
- **WHEN** a subscriber has several pending signals for the same topic and resource
- **THEN** only the latest one is kept, and signals for other resources are kept

#### Scenario: A client lags repeatedly
- **WHEN** a subscriber overflows its bound three times within 60 seconds
- **THEN** it receives a `reconnect` instruction and its stream is closed

#### Scenario: A write blocks
- **WHEN** writing to a subscriber stays blocked for more than 30 seconds
- **THEN** the subscriber is sent a `reconnect` instruction where possible and closed, and its resources are released

#### Scenario: A dropped client recovers
- **WHEN** a subscriber is dropped for lag
- **THEN** it reconnects and receives the replay it missed, or a full-refresh signal when the gap is too large

#### Scenario: The replay hold buffer overflows
- **WHEN** events arrive during a replay faster than the hold buffer can keep
- **THEN** the buffer stays bounded and the subscriber is told to resync with a full refresh

#### Scenario: Abuse by many slow clients
- **WHEN** many subscribers stall at once
- **THEN** Studio's memory stays bounded and other subscribers are unaffected
