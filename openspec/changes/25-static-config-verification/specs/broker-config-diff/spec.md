## ADDED Requirements

### Requirement: Static drift is reported per node
Studio SHALL compare declared and actual static values per node and report each difference with the node, setting, expected and actual value. It SHALL NOT change the broker.

#### Scenario: One node differs
- **WHEN** node B has a different global size
- **THEN** only node B is reported, with both values

#### Scenario: All nodes match
- **WHEN** actual equals declared everywhere
- **THEN** no drift is reported

### Requirement: Static drift can alert and fail a check
Static drift SHALL be an alertable condition, and a machine-readable check result SHALL let the command-line tool exit with failure in CI when drift exists.

#### Scenario: Drift raises an alert
- **WHEN** static drift persists
- **THEN** the alerting engine can fire a rule on it

#### Scenario: CI checks a cluster
- **WHEN** the check runs while drift exists
- **THEN** the result reports failure and lists the differences

#### Scenario: An unreadable setting in CI
- **WHEN** a declared setting cannot be read
- **THEN** the result reports it as unverifiable, and the caller can choose to treat it as failure
