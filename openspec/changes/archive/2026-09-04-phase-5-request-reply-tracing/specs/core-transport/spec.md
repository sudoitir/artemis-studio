## MODIFIED Requirements

### Requirement: Core connections are released on shutdown and cluster removal

The system SHALL pool Core connections per cluster rather than opening and
closing one per call, and SHALL close every pooled Core connection for a cluster
when that cluster is removed, and SHALL close every pooled Core connection on
application shutdown without letting a hung close delay shutdown beyond a
bounded wait.

#### Scenario: Removing a cluster closes its connections

- **WHEN** a cluster is removed
- **THEN** its Core subscriptions and pooled connections are closed and not reopened

#### Scenario: Shutdown is not blocked by a hung close

- **WHEN** the application shuts down and one Core connection does not close promptly
- **THEN** shutdown proceeds after a bounded wait

#### Scenario: Repeated calls to the same node reuse a pooled connection

- **WHEN** two consecutive Core operations target the same cluster and Core URL
- **THEN** the second operation reuses a pooled connection rather than opening a new one
