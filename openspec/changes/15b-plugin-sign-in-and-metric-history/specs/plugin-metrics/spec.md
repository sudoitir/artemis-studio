## ADDED Requirements

### Requirement: A plugin can read metric history under its acting user's permissions
The plugin API SHALL let a plugin read the metric history Studio keeps, for a named metric, subject and time range, on behalf of an acting user, returning only series that user may read and applying the existing range and size bounds.

#### Scenario: Reading a queue's depth history
- **WHEN** a plugin reads the depth history of a queue on a cluster its acting user may read
- **THEN** it receives the samples within the retention window

#### Scenario: A cluster the user may not read
- **WHEN** a plugin reads history of a cluster its acting user may not read
- **THEN** it receives the same answer as for a cluster that does not exist

#### Scenario: A range beyond retention
- **WHEN** a plugin asks for a range older than the retention window
- **THEN** the range is clamped and the answer says so

#### Scenario: Background work for a user who lost access
- **WHEN** a plugin reads history in the background on behalf of the user who configured that work, and that user can no longer read the cluster
- **THEN** it receives the same answer as for a cluster that does not exist
