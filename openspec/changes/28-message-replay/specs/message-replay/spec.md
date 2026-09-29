## ADDED Requirements

### Requirement: Captured messages can be replayed to a chosen target
The system SHALL let a user replay one captured message, a selection or a filtered batch to a queue or address they choose, preserving the original headers unless edited.

#### Scenario: Single replay
- **WHEN** a user replays one captured message to a queue
- **THEN** the message is sent with its original body and headers

#### Scenario: Batch replay
- **WHEN** a user replays a filtered set
- **THEN** every message in the set is sent once, in capture order

### Requirement: A replay can transform the message
The user SHALL be able to edit headers and body before replay, per message or by a rule over a batch, and see the result before sending.

#### Scenario: A header is corrected
- **WHEN** a rule sets a header on a batch
- **THEN** the preview shows the new value and the sent messages carry it

#### Scenario: A masked body
- **WHEN** governance masks a field
- **THEN** the user cannot read or edit the masked value

### Requirement: A replay can be dry-run
A replay SHALL support a dry run that validates every message and the target and reports the outcome without sending.

#### Scenario: Dry run
- **WHEN** a batch is dry-run
- **THEN** nothing is sent and the report lists messages that would fail and why

### Requirement: A replay is rate limited and stoppable
A replay SHALL send within a configurable rate limit, be stoppable, and report sent, failed and remaining counts.

#### Scenario: Stopped midway
- **WHEN** a user stops a replay
- **THEN** no further messages are sent and the counts are reported

#### Scenario: Abuse by flooding
- **WHEN** a user sets an extreme rate
- **THEN** the rate is capped at the administrator's maximum

### Requirement: The target is validated before anything is sent
Studio SHALL check that the target exists, the user has permission to send to it and each message fits its size limit, and SHALL refuse the replay otherwise.

#### Scenario: Target missing
- **WHEN** the queue does not exist
- **THEN** the replay is refused before sending

#### Scenario: No permission
- **WHEN** the user may not send to the target
- **THEN** the replay is refused

### Requirement: Every replay is audited and linked to lineage
Each replay SHALL be audited with the user, source, target, count and transformation, and each replayed message SHALL be linked to its original in lineage.

#### Scenario: Replay completes
- **WHEN** a replay finishes
- **THEN** an audit event exists and lineage shows the replay hop from the original

### Requirement: A replay never sends masked values as if they were real
Captured content is stored masked, with originals of sensitive values sealed. A replay SHALL send sealed originals only for a user holding the clear-content permission on the source cluster, and SHALL audit that as a clear view. For any other user, or for a credential, which is never sealed, the replay SHALL refuse the message and name the field, unless the user replaces the value by an edit.

#### Scenario: A user without clear access
- **WHEN** a user without the clear-content permission replays a message whose email field was masked
- **THEN** that message is refused naming the field, and nothing carrying the mask placeholder is sent

#### Scenario: A user with clear access
- **WHEN** a user with the clear-content permission replays the same message
- **THEN** the original value is sent and a clear-view audit record is written

### Requirement: A replay survives interruption without loss or duplicates and carries provenance
A replay SHALL follow the guarantees of a cross-broker transfer: an interruption SHALL neither lose nor duplicate a message, and each replayed message SHALL carry provenance naming the original.

#### Scenario: Studio restarts mid-replay
- **WHEN** Studio stops after sending half of a batch and starts again
- **THEN** the replay resumes and each message is sent exactly once in total
