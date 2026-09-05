## ADDED Requirements

### Requirement: Message operations require the matching permission at the cluster's scope

Browsing messages SHALL require a read permission at the target cluster's
scope. Sending, moving, retrying, deleting, expiring, or purging messages
SHALL each require the corresponding write permission at the target cluster's
scope, including when performed as a dry run.

#### Scenario: Browse requires read permission

- **WHEN** a user without read permission on a cluster attempts to browse a
  queue on it
- **THEN** the request is rejected

#### Scenario: Purge requires the purge permission

- **WHEN** a user holding read but not purge permission on a cluster attempts
  to purge a queue, dry run or not
- **THEN** the request is rejected
