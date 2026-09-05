## ADDED Requirements

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
