## ADDED Requirements

### Requirement: Queue lifecycle permissions

The system SHALL define distinct permissions for creating a queue or address,
destroying a queue or address, updating a queue's configuration, and pausing or
resuming a queue. Each SHALL be resolvable at global, environment, or cluster
scope through the existing scope walk, and SHALL appear in the permission
catalogue with a human label so the role editor presents it without a client-side
change.

Holding a message-level permission SHALL NOT imply any lifecycle permission.
Purging a queue and destroying a queue are different authorities: one empties a
resource the operator keeps, the other removes the resource itself.

#### Scenario: Purge does not imply destroy

- **WHEN** a role holds the queue-purge permission but not the destroy permission
- **THEN** its holder can purge a queue and cannot destroy one

#### Scenario: A lifecycle permission is scoped like every other

- **WHEN** a role is granted the create permission on a single environment
- **THEN** its holder can create queues on clusters in that environment and not on
  clusters outside it

#### Scenario: A new permission needs no client change to be assignable

- **WHEN** the role editor is opened after the lifecycle permissions are added
- **THEN** each appears with its label and can be granted
