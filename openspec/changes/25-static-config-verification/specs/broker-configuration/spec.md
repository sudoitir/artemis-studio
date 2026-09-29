## ADDED Requirements

### Requirement: Expected values can be declared for static settings
An operator SHALL be able to declare expected values for broker settings the management API cannot apply, including maximum global size, the HA policy and acceptors, as verify-only entries of a cluster's declaration. An apply SHALL never try to write them.

#### Scenario: A setting is declared
- **WHEN** an operator declares an expected global size
- **THEN** the declaration stores it as verify-only and the next apply plan contains no step for it

#### Scenario: An unsupported setting
- **WHEN** the operator names a setting Studio cannot read from a node
- **THEN** the declaration is refused and states the reason

### Requirement: Importing broker XML keeps readable static settings as verify-only
When a pasted `broker.xml` or `<core>` fragment holds a static setting Studio can read from a node, the import SHALL offer it as a verify-only entry instead of listing it as unsupported. Settings Studio cannot read SHALL still be listed as unsupported.

#### Scenario: Global size in an import
- **WHEN** an operator imports a fragment containing `<global-max-size>`
- **THEN** the preview offers it as a verify-only entry and does not list it as unsupported

### Requirement: Actual static values are read from each node
Studio SHALL read the actual value of each declared static setting from each live node where the broker exposes it, and SHALL say which settings it could not read.

#### Scenario: A node reports a value
- **WHEN** a node is read
- **THEN** the actual value is stored with its read time

#### Scenario: A value is not exposed
- **WHEN** a node does not report a setting
- **THEN** it is shown as unverifiable and not as matching

### Requirement: Static drift is reported per node by the existing drift report
The drift report SHALL classify declared static settings like every other declared item, per node, with the expected and actual value, and SHALL NOT change the broker.

#### Scenario: One node differs
- **WHEN** node B has a different global size than declared
- **THEN** only node B is reported, with both values

#### Scenario: All nodes match
- **WHEN** actual equals declared everywhere
- **THEN** no static drift is reported

### Requirement: The drift check can fail a CI job
A machine-readable drift result SHALL let the command-line tool exit with a failure code when drift exists.

#### Scenario: CI checks a cluster
- **WHEN** the check runs while drift exists
- **THEN** the result reports failure and lists the differences

#### Scenario: An unreadable setting in CI
- **WHEN** a declared setting cannot be read
- **THEN** the result reports it as unverifiable, and the caller can choose to treat it as failure
