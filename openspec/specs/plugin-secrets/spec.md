# plugin-secrets Specification

## Purpose
How a runtime plugin keeps secrets: a vault per plugin, encrypted with Studio's key, never returned through any interface (ADR-0111).

## Requirements

### Requirement: Each plugin has its own secret vault

The system SHALL let a plugin store, replace, read and delete named secrets in a vault scoped to that plugin, encrypted at rest with Studio's existing secret protection. A plugin SHALL NOT read another plugin's secrets. Uninstalling or purging a plugin SHALL delete its secrets.

#### Scenario: Plugins are isolated
- **WHEN** one plugin requests a secret name that another plugin stored
- **THEN** it does not receive that secret

#### Scenario: Purge deletes secrets
- **WHEN** a plugin is purged
- **THEN** none of its secrets remain

### Requirement: Secrets never leave the server

The system SHALL NOT return a stored secret value through any HTTP, stream or assistant interface. It SHALL NOT write a secret value to logs, audit records or error messages. An interface MAY report only whether a secret is set and when it last changed.

#### Scenario: A secret is not echoed
- **WHEN** a client reads a resource that references a stored secret
- **THEN** the response states that the secret is set and contains no part of its value
