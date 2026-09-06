## ADDED Requirements

### Requirement: Divert write permission

The system SHALL define a permission for creating and deleting diverts, resolvable
at global, environment, or cluster scope through the existing scope walk, and listed
in the permission catalogue with a human label.

Viewing diverts and bridges SHALL require only the permission to view the cluster.
No permission SHALL grant mutation of a bridge, because no such operation exists.

#### Scenario: Viewing needs no additional grant

- **WHEN** a caller who may view a cluster opens its divert view
- **THEN** the diverts are listed

#### Scenario: Creating needs the divert permission

- **WHEN** a caller without the divert permission attempts to create a divert
- **THEN** the request is refused
