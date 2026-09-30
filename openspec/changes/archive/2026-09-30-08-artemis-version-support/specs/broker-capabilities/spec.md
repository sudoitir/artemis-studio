## ADDED Requirements

### Requirement: A supported Artemis version range is stated
The documentation SHALL state the minimum (2.33.0) and latest tested (2.57.0) Artemis versions, the images
they are tested with, and how the range was established. The range SHALL be defined once in the backend and
used by registration, the UI and CI.

#### Scenario: Reading the matrix
- **WHEN** a user opens the supported versions page
- **THEN** the minimum, the latest tested version and the images CI tests are listed, with how the minimum was chosen

#### Scenario: Classic
- **WHEN** a user looks for ActiveMQ Classic
- **THEN** the page states it is not supported

### Requirement: Every operation works across the supported range, or falls back
An operation whose management call is newer than the minimum SHALL fall back to an equivalent call the older
broker has, when one exists. Creating a divert SHALL use the JSON `createDivert` and, on a broker without it
(before 2.38.0), the positional form carrying the same fields.

#### Scenario: Divert on a broker older than 2.38
- **WHEN** a divert is created, directly or by message capture, a plugin tap or a configuration apply, on a broker older than 2.38.0
- **THEN** the divert is deployed with the requested name, routing name, addresses, exclusivity, filter and routing type

### Requirement: Capabilities requiring a newer broker are gated and explained
An operation with no fallback that needs a broker version above a node's version SHALL be unavailable on that
node. It SHALL be reported with the cluster's capabilities in the three-state capability status, with its
reason and the release it needs, and the UI SHALL state the release where the action would be.

#### Scenario: Older broker
- **WHEN** an operation needs a newer version than every node of the cluster runs
- **THEN** its status is unavailable, and the control is disabled with the required version stated

#### Scenario: Mixed versions in one cluster
- **WHEN** the nodes of one cluster run different versions, as during a rolling broker upgrade
- **THEN** each node's version is shown, the operation runs only on the nodes whose version supports it, and each other node is reported as skipped for its version, not as failed

#### Scenario: Version not known
- **WHEN** no node has reported its version
- **THEN** the operation's status is unknown, and the control stays enabled with the uncertainty stated

### Requirement: CI tests the minimum and the latest supported versions
The continuous integration pipeline SHALL run the integration suite against a broker at the minimum and at the
latest tested version.

#### Scenario: Range moves
- **WHEN** the supported range changes in the backend
- **THEN** the build fails until the pipeline's broker versions change with it
