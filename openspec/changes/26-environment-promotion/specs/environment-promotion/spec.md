## ADDED Requirements

### Requirement: Two environments or clusters can be compared
The system SHALL compare queues, addresses, diverts, bridges and security settings between two environments or two clusters, and classify each item as equal, different, only in source or only in target.

#### Scenario: A queue exists only in the source
- **WHEN** the source has a queue the target lacks
- **THEN** the comparison lists it as only in source

#### Scenario: Different settings
- **WHEN** a queue differs in a setting
- **THEN** the diff names the setting and both values

#### Scenario: Permission
- **WHEN** a user cannot read the target
- **THEN** no comparison is made

### Requirement: Differences are previewed and selected before promotion
The interface SHALL show the diff, let the user select the differences to promote, and show the exact changes and their hazards before anything is applied.

#### Scenario: A user selects two differences
- **WHEN** they select a queue and a divert
- **THEN** the preview lists only those changes and their hazards

### Requirement: A promotion can be run as a dry run
A promotion SHALL support a dry run that validates every step against the target and reports what would change, without changing it.

#### Scenario: Dry run
- **WHEN** a promotion is dry-run
- **THEN** nothing on the target changes and the report lists each step's outcome

#### Scenario: A step would fail
- **WHEN** a step is invalid on the target
- **THEN** the dry run reports it and the real run is not allowed to proceed unchanged

### Requirement: A promotion can be rolled back
For every applied promotion the system SHALL record the prior state of what it changed and SHALL offer a rollback that restores it, stating what cannot be restored.

#### Scenario: Roll back
- **WHEN** a user rolls back a promotion
- **THEN** the target returns to the recorded state for everything restorable

#### Scenario: Something is not restorable
- **WHEN** a change cannot be undone
- **THEN** the rollback says so before running and leaves that part untouched

### Requirement: A promotion is held for approval when a gate is installed
When an approval gate is installed, a promotion SHALL be submitted to it and applied only after approval. When none is installed, the promotion SHALL proceed under the requester's permissions.

#### Scenario: Gate installed
- **WHEN** a promotion is requested
- **THEN** it waits for approval and applies only after it is granted

#### Scenario: No gate
- **WHEN** a promotion is requested
- **THEN** it applies as normal under the requester's permissions

### Requirement: Configuration can be exported and imported as YAML
The system SHALL export a cluster or environment's configuration as YAML and import one into a target with the same preview, dry run and audit as a promotion.

#### Scenario: Round trip
- **WHEN** a configuration is exported and imported into an empty cluster
- **THEN** the cluster's configuration matches the file

#### Scenario: Invalid file
- **WHEN** the YAML is invalid
- **THEN** nothing is applied and the errors are listed

### Requirement: Every promotion is audited
Every promotion, dry run, rollback and import SHALL be audited once, with who, source, target, the changes and the outcome.

#### Scenario: A promotion completes
- **WHEN** it finishes
- **THEN** one audit event records it with its changes and outcome
