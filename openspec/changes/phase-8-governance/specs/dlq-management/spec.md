## ADDED Requirements

### Requirement: DLQ operations require the same permission as the message path they use

Viewing the DLQ SHALL require read permission at the cluster's scope. Replaying
messages from the DLQ, including replay-all, SHALL require the same write
permission the underlying move or retry operation requires.

#### Scenario: Replay-all requires write permission

- **WHEN** a user without write permission on a cluster attempts a DLQ
  replay-all
- **THEN** the request is rejected
