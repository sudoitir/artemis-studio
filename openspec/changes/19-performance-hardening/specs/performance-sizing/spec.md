## ADDED Requirements

### Requirement: Sizing targets are measured and published
Studio SHALL publish a sizing guide stating, for a reference machine, the supported number of clusters, nodes per cluster, queues and messages per node, with the measured result behind each figure.

#### Scenario: A figure has a measurement
- **WHEN** a target appears in the guide
- **THEN** the guide names the load profile, the machine and the result that support it

#### Scenario: Targets are stated as limits
- **WHEN** an operator reads the guide
- **THEN** each dimension has a tested ceiling and says what degrades first beyond it

### Requirement: The load test is repeatable from the repository
The repository SHALL contain a load test that a contributor can run with one documented command against a local Studio and a simulated or real broker set, and that reports the same metrics the guide cites.

#### Scenario: A contributor reruns the test
- **WHEN** the documented command is run on a clean checkout
- **THEN** it produces the metrics the guide reports, without manual setup beyond the documented prerequisites

#### Scenario: A regression is visible
- **WHEN** a change makes a target worse
- **THEN** the run shows the figure below the published target

### Requirement: Results are recorded in the guide when they change
Each run that changes a published figure SHALL update the guide with the new figure, the date and the version measured.

#### Scenario: A figure moves
- **WHEN** a later run produces a different result
- **THEN** the guide shows the new figure with its version and date
