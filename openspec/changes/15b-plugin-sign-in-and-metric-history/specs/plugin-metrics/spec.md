## ADDED Requirements

### Requirement: A plugin can read metric history under its acting user's permissions
The plugin API SHALL let a plugin read the metric history Studio keeps on behalf of an acting user it names: the queue and cluster metrics Studio samples, and any running plugin's declared metrics. A read names the acting user, the cluster, the metric or metrics, the subject and the time range, and SHALL return the same series, buckets, range and size bounds and truncation flag as the metrics read API. The acting user's account and grants SHALL be read as they stand at the time of the read. A read SHALL answer exactly as for a cluster that does not exist when the acting user is missing, unknown or disabled, or may not read the cluster, and SHALL be refused as the read API refuses it when the user lacks a plugin metric's declared permission.

#### Scenario: Reading a queue's depth history
- **WHEN** a plugin reads the depth history of a queue on a cluster its acting user may read
- **THEN** it receives the samples within the retention window, in the read API's buckets

#### Scenario: Reading another plugin's metric
- **WHEN** a plugin reads a running plugin's metric for a user who holds the metric's declared permission on the cluster
- **THEN** it receives that metric's series for the subject

#### Scenario: A cluster the user may not read
- **WHEN** a plugin reads history of a cluster its acting user may not read
- **THEN** it receives the same answer as for a cluster that does not exist

#### Scenario: A range beyond retention
- **WHEN** a plugin asks for a range older than the retention window
- **THEN** the range is clamped and the answer says so

#### Scenario: Background work for a user who lost access
- **WHEN** a plugin reads history in the background on behalf of the user who configured that work, and that user can no longer read the cluster
- **THEN** it receives the same answer as for a cluster that does not exist

#### Scenario: A disabled acting user
- **WHEN** a plugin reads history on behalf of a user who has been disabled
- **THEN** it receives the same answer as for a cluster that does not exist
