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

### Requirement: Capture write permission

The system SHALL define a permission for creating, modifying and deleting message capture
subscriptions, resolvable at global, environment, or cluster scope through the existing
scope walk, and listed in the permission catalogue with a human label.

This permission SHALL be distinct from the permission that governs Studio's own settings.
Capture mutates broker routing configuration and creates a stored copy of application
payload; a grant that lets an operator change Studio's configuration SHALL NOT thereby let
them tap a production address.

Querying captured messages SHALL require the same message read permission, scoped the same
way, as querying a broker directly.

#### Scenario: Capture needs its own grant

- **WHEN** a caller holding the settings write permission but not the capture permission attempts to create a capture subscription
- **THEN** the request is refused

#### Scenario: Reading captured payload needs the message read permission

- **WHEN** a caller without message read permission on a cluster queries its captured messages
- **THEN** the request is refused
