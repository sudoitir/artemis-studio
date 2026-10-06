## MODIFIED Requirements

### Requirement: Opening the stream requires cluster read permission

The system SHALL require authentication to open the event stream, and SHALL require the caller to
be able to read the requested cluster, through a grant or a team, rejecting the subscription
otherwise. Each topic SHALL declare the permission it needs and how an event names the resource it
concerns. An event SHALL be delivered to a subscriber only when the subscriber holds the topic's
permission on that event's resource, or on the cluster for an event about no single resource; an
event that lists several resources SHALL be trimmed to those the subscriber may read. When a
subscriber's access changes, the next event SHALL be decided on the new access, without
reconnecting.

#### Scenario: Unauthenticated stream request is rejected

- **WHEN** a client with no authenticated session or token opens the stream
- **THEN** the connection is rejected

#### Scenario: Stream request for an ungranted cluster is rejected

- **WHEN** an authenticated client opens the stream for a cluster it holds no
  read grant on and no team on
- **THEN** the connection is rejected

#### Scenario: Events about another team's queue are not delivered

- **WHEN** an Orders-only subscriber is connected and queue `billing.in` changes
- **THEN** the subscriber receives no event about `billing.in`

#### Scenario: A topic needs its feature permission

- **WHEN** a subscriber without `alert:read` is connected and an alert fires
- **THEN** the subscriber receives no alert event

#### Scenario: Revocation applies to an open stream

- **WHEN** a subscriber is removed from team Orders while connected
- **THEN** no further event about Orders' queues reaches it
