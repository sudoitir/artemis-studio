## ADDED Requirements

### Requirement: Connection control permission

The system SHALL define a permission for closing connections, sessions and an
address's consumers, resolvable at global, environment, or cluster scope through
the existing scope walk, and listed in the permission catalogue with a human label.

This permission SHALL be independent of every message permission. Authority over a
cluster's messages does not imply authority to disconnect the applications
producing and consuming them.

#### Scenario: Independent of message authority

- **WHEN** a role holds message send, move, delete and queue purge, and not the
  connection-close permission
- **THEN** its holder cannot close a connection

#### Scenario: Scoped like every other permission

- **WHEN** the connection-close permission is granted on one cluster
- **THEN** its holder can close connections on that cluster and on no other
