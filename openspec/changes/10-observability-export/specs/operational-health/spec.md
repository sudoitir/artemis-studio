## ADDED Requirements

### Requirement: Studio shows its own health in one view
Studio SHALL show poller and scrape lag, management call latency, database pool use, event stream client count and each job's status.

#### Scenario: Lag rising
- **WHEN** the poller falls behind
- **THEN** the view shows the lag and the state as degraded

#### Scenario: Unavailable figure
- **WHEN** a figure cannot be read
- **THEN** it is shown as unavailable, never as zero
