## ADDED Requirements

### Requirement: Lifecycle commands can be issued on behalf of a drift finding

The lifecycle capability SHALL accept a reference to the drift finding that
motivated a command, and SHALL record it on the audit event, so that a resource
created or destroyed to resolve drift is traceable to the finding without a second
kind of audit event existing.

The presence of such a reference SHALL NOT change any of the command's behaviour —
the same permission, preview, safety cap, confirmation and per-node outcome apply.

#### Scenario: A drift-motivated command is traceable

- **WHEN** a queue is created to resolve a drift finding
- **THEN** the audit event for the creation references the finding

#### Scenario: A drift reference grants nothing

- **WHEN** a lifecycle command referencing a drift finding is issued by a caller
  without the permission for that command
- **THEN** it is refused exactly as it would be without the reference
