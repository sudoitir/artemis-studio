## ADDED Requirements

### Requirement: Expected values can be declared for static settings
An operator SHALL be able to declare expected values for broker settings the management API cannot apply, including maximum global size, the HA policy and acceptors, as part of a cluster's declaration.

#### Scenario: A setting is declared
- **WHEN** an operator declares an expected global size
- **THEN** the declaration stores it and marks it verify-only

#### Scenario: An unsupported setting
- **WHEN** the operator names a setting Studio cannot read
- **THEN** the declaration is refused and states the reason

### Requirement: Actual static values are read from each node
Studio SHALL read the actual value of each declared static setting from each node where the broker exposes it, and SHALL say which settings it could not read.

#### Scenario: A node reports a value
- **WHEN** a node is read
- **THEN** the actual value is stored with its read time

#### Scenario: A value is not exposed
- **WHEN** a node does not report a setting
- **THEN** it is shown as unverifiable and not as matching
