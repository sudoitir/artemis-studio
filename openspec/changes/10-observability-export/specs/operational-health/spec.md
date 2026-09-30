## MODIFIED Requirements

### Requirement: Resource growth and broker load are observable

The system SHALL expose, on its metrics endpoint:
- its live thread count;
- the number of management requests issued per node;
- the time callers waited for that node's rate-limit capacity, and how often they gave up;
- the latency and outcome of management calls and message-transport calls per node;
- how far each background job is behind its schedule;
- the number of connected event stream clients;
- the database connection pool's active, idle, maximum and pending connections.

A node SHALL be identified in metrics by its host and port only, never by a URL that could
carry credentials. An operator SHALL be able to tell from these metrics alone two things: whether
Studio's own resource use is growing, and whether it is loading a broker at its ceiling.

#### Scenario: Per-node request volume is visible

- **WHEN** Studio issues management requests to a node
- **THEN** the metrics endpoint reports the count of requests issued to that node and the rate-limit wait incurred

#### Scenario: Thread count is visible

- **WHEN** the metrics endpoint is scraped
- **THEN** it reports the live thread count

#### Scenario: A node's credentials never reach a metric label

- **WHEN** a node's management URL contains a user name and password
- **THEN** its metrics are labelled with the host and port only

#### Scenario: Job lag is measured

- **WHEN** a job has not completed a run for longer than its interval
- **THEN** the metrics endpoint reports that job's lag as the time past its interval, and zero while it keeps to schedule

## ADDED Requirements

### Requirement: Studio shows its own health in one view

The system SHALL show, on one screen for holders of the settings-read permission:
- each background job's status and lag;
- each node's last management success and failure, management call latency and rate-limit wait;
- database pool use;
- the event stream client count.

Any job or node that is degraded SHALL be marked, and so SHALL the screen as a whole. The screen
SHALL refresh on its own.

#### Scenario: Lag rising

- **WHEN** the poller falls behind
- **THEN** the view shows the lag and marks the job and the view as degraded

#### Scenario: Unavailable figure

- **WHEN** a figure cannot be read
- **THEN** it is shown as unavailable, never as zero

#### Scenario: Without permission

- **WHEN** a user without the settings-read permission requests the health read
- **THEN** the request is refused
