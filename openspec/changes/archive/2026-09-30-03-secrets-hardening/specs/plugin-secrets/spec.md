## MODIFIED Requirements

### Requirement: Each plugin has its own secret vault

The system SHALL let a plugin store, replace, read and delete named secrets in a vault scoped to that plugin. Each entry SHALL be envelope encrypted at rest like every other stored secret (`secret-management`). The contract a plugin uses to read and write entries SHALL NOT change. A plugin SHALL NOT read another plugin's secrets. Uninstalling or purging a plugin SHALL delete its secrets.

#### Scenario: Plugins are isolated
- **WHEN** one plugin requests a secret name that another plugin stored
- **THEN** it does not receive that secret

#### Scenario: Purge deletes secrets
- **WHEN** a plugin is purged
- **THEN** none of its secrets remain

#### Scenario: Plugin reads its secret
- **WHEN** a plugin reads a vault entry
- **THEN** it receives the plaintext through the unchanged contract

#### Scenario: Entry survives a key rotation
- **WHEN** a plugin stored a secret before a key-encryption key rotation
- **THEN** it reads the same value after the rotation
