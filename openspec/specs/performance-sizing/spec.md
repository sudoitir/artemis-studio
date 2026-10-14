# performance-sizing Specification

## Purpose
How much Rahdar handles on stated hardware: a load test that drives a replicated Rahdar through its real API, a sizing guide that publishes each dimension's measured ceiling and first degradation, the hardware requirements derived from them, and a regression check against those figures (ADR-0198).

## Requirements

### Requirement: Sizing targets are measured and published
Rahdar SHALL publish a sizing guide stating, for a reference machine of 4 vCPU and 8 GB of memory, the supported number of clusters, nodes per cluster, queues and messages per node, with the measured result behind each figure.

#### Scenario: A figure has a measurement
- **WHEN** a target appears in the guide
- **THEN** the guide names the load profile, the machine, the Rahdar version, the date and the result that support it

#### Scenario: Targets are stated as limits
- **WHEN** an operator reads the guide
- **THEN** each dimension has a tested ceiling and says what degrades first beyond it

### Requirement: The load test is repeatable from the repository
The repository SHALL contain a load test that a contributor can run locally with one documented command against a replicated Rahdar and a simulated or real broker set, and that reports the same metrics the guide cites. The same test SHALL also run through a workflow that is started by hand. Its profiles SHALL cover ordinary UI reads, event-stream clients that read fast and clients that stall, exports, and the SQL console.

#### Scenario: A contributor reruns the test
- **WHEN** the documented command is run on a clean checkout
- **THEN** it starts its own stack, seeds data, runs the profiles and produces the metrics the guide reports, without manual setup beyond the documented prerequisites

#### Scenario: The test runs from a workflow
- **WHEN** a maintainer starts the load-test workflow by hand
- **THEN** it runs the same profiles and publishes the same report

#### Scenario: A regression is visible
- **WHEN** a change makes a target worse
- **THEN** the run shows the figure below the published target and marks it as a regression

### Requirement: Results are recorded in the guide when they change
Each run that changes a published figure SHALL update the guide with the new figure, the date and the version measured.

#### Scenario: A figure moves
- **WHEN** a later run produces a different result
- **THEN** the guide shows the new figure with its version and date

### Requirement: The README states hardware requirements from measurements
The README SHALL have a short hardware section giving a minimum and a recommended size for the Rahdar node and for Postgres, for a single instance and for a replicated deployment. Each figure SHALL be derived from the load test and the section SHALL link to the sizing guide.

#### Scenario: An operator plans a deployment
- **WHEN** an operator reads the README before installing
- **THEN** they find minimum and recommended sizes for Rahdar and Postgres, single and replicated, and a link to the measurements behind them

#### Scenario: A measurement changes a figure
- **WHEN** a load-test run changes a figure in the sizing guide that the README states
- **THEN** the README's figure is updated in the same change

### Requirement: Heavy endpoints are bounded like the SQL console
Every endpoint whose cost grows with the data it covers, such as exports, bulk and transfer previews, flow and topology reads and audit and alert history, SHALL have a time bound, a row bound and a per-user concurrency limit, and SHALL say which bound it reached, rows or time. The SQL console already meets this and is the model. Concurrency permits SHALL be counted across all replicas, so that a user's limit holds whichever replica serves each request. Large results SHALL be paged by key.

#### Scenario: An export too large
- **WHEN** an export would exceed its row bound
- **THEN** it stops at the bound and states that the result is incomplete and that the row bound was reached

#### Scenario: An export too slow
- **WHEN** a request reaches its time bound
- **THEN** it stops and states that the time bound was reached

#### Scenario: One user floods an endpoint
- **WHEN** a user starts more heavy requests than the limit
- **THEN** the extra ones are refused with a message naming the limit, and other users are unaffected

#### Scenario: The limit holds across replicas
- **WHEN** a user's heavy requests are spread over several replicas
- **THEN** the limit applies to their total and the extra requests are refused whichever replica receives them

#### Scenario: A holder disappears
- **WHEN** a replica dies while its requests hold permits
- **THEN** the permits lapse after a lease time and do not block the user permanently

### Requirement: Per-user and per-node limits hold across replicas
Limits on sign-in attempts, uploads and in-flight requests per user SHALL be counted in the shared database, not per replica. The rate limit on management calls to a broker node SHALL be divided by the number of ready replicas, so that all replicas together stay within the node's limit.

#### Scenario: Sign-in attempts are spread over replicas
- **WHEN** failed sign-ins for one account arrive at different replicas
- **THEN** the lockout counts all of them

#### Scenario: Replicas share a node's rate
- **WHEN** several replicas scrape the same node
- **THEN** their combined management calls stay within the node's limit
