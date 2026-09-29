## ADDED Requirements

### Requirement: A supported Artemis version range is stated
The documentation SHALL state the minimum and latest supported Artemis versions and how they were established.

#### Scenario: Reading the matrix
- **WHEN** a user opens the support page
- **THEN** the range and the tested versions are listed

#### Scenario: Classic
- **WHEN** a user looks for ActiveMQ Classic
- **THEN** the page states it is not supported

### Requirement: Capabilities requiring a newer broker are gated and explained
A capability that needs a broker version above the registered one SHALL be unavailable, and the UI SHALL say which version is needed where the action would be.

#### Scenario: Older broker
- **WHEN** a feature needs a newer version than the broker runs
- **THEN** the control is disabled with the required version stated

### Requirement: CI tests the minimum and the latest supported versions
The continuous integration pipeline SHALL run integration tests against a broker at the minimum and at the latest version in range.

#### Scenario: Range moves
- **WHEN** the supported range changes
- **THEN** the pipeline versions change with it
