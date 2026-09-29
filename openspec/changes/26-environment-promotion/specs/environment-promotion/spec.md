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

#### Scenario: Promotion needs write on the target
- **WHEN** a user who may read both sides but may not write the target's configuration promotes
- **THEN** it is refused before any revision is saved

### Requirement: Differences are previewed and selected before promotion
The interface SHALL show the diff, let the user select the differences to promote, and show the exact changes and their hazards before anything is applied.

#### Scenario: A user selects two differences
- **WHEN** they select a queue and a divert
- **THEN** the preview lists only those changes and their hazards

### Requirement: A promotion goes through the target's declaration and apply
A promotion SHALL save the selected differences as a new revision of the target cluster's declaration, naming the source, and SHALL change brokers only through the existing apply, with its plan, hazards, canary-first order and halt on failure. A target whose configuration is managed outside Studio SHALL receive the revision and SHALL NOT be applied to.

#### Scenario: A promotion to a managed cluster
- **WHEN** a promotion of two queues is confirmed
- **THEN** the target has a new declaration revision naming the source, and the apply plan for it is the one the user previewed

#### Scenario: A stale target
- **WHEN** the target's declaration changed after the preview
- **THEN** the promotion is refused, as a stale save is today

#### Scenario: Bridge credentials
- **WHEN** a promoted bridge uses a stored credential on the source
- **THEN** the credential is not copied, and the target reports the bridge as needing its own credential before it can be applied

### Requirement: A promotion can be run as a dry run
A promotion SHALL support a dry run that validates every step against the target and reports what would change, without changing it.

#### Scenario: Dry run
- **WHEN** a promotion is dry-run
- **THEN** nothing on the target changes and the report lists each step's outcome

#### Scenario: A step would fail
- **WHEN** a step is invalid on the target
- **THEN** the dry run reports it and the real run is not allowed to proceed unchanged

### Requirement: A promotion can be rolled back
For every applied promotion the system SHALL offer a rollback that applies the target's previous declaration revision, stating beforehand what cannot be restored. A rollback SHALL never destroy a queue or an address, since the apply never does.

#### Scenario: Roll back
- **WHEN** a user rolls back a promotion
- **THEN** the target returns to the recorded state for everything restorable

#### Scenario: Something is not restorable
- **WHEN** a promotion created a queue that now holds messages
- **THEN** the rollback says the queue stays, leaves it untouched and points to where it can be deleted

### Requirement: A promotion is held for approval when a gate is installed
When an approval gate is installed, a promotion SHALL be submitted to it and applied only after approval. When none is installed, the promotion SHALL proceed under the requester's permissions.

#### Scenario: Gate installed
- **WHEN** a promotion is requested
- **THEN** it waits for approval and applies only after it is granted

#### Scenario: No gate
- **WHEN** a promotion is requested
- **THEN** it applies as normal under the requester's permissions

### Requirement: An environment's declarations can be exported and imported as files
The system SHALL export every cluster declaration of an environment as files in the declaration's existing XML interchange format, one per cluster, and SHALL import such files into a target with the same preview, dry run and audit as a promotion. It SHALL NOT introduce a second interchange format.

#### Scenario: Round trip
- **WHEN** an environment is exported and its files are imported into another environment with clusters of the same names
- **THEN** each target declaration equals the exported one

#### Scenario: Invalid file
- **WHEN** a file does not parse
- **THEN** nothing is applied and the errors are listed

#### Scenario: A file for an unknown cluster
- **WHEN** an imported file names a cluster that is not in the target environment
- **THEN** that file is refused and the others are previewed

### Requirement: Every promotion is audited
Every promotion, dry run, rollback and import SHALL be audited once, with who, source, target, the changes and the outcome.

#### Scenario: A promotion completes
- **WHEN** it finishes
- **THEN** one audit event records it with its changes and outcome
