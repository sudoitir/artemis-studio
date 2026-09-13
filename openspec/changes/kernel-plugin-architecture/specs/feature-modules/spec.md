## Purpose

Defines how an installation of Studio is composed from modules. It covers what a feature declares, which modules are required, how an operator enables or disables a feature at startup, the manifest that tells clients what this installation offers, and how a disabled feature behaves everywhere it would otherwise appear.

## ADDED Requirements

### Requirement: A feature declares what it contributes

Every feature SHALL declare a stable identifier, a human title, the contract version it was built against, the features it requires, and the permissions, operational settings, stream topics and agent-surface tools it contributes. The installation's catalogue of permissions, settings, topics and tools SHALL be assembled from these declarations and from nowhere else.

#### Scenario: Declarations assemble the catalogue

- **WHEN** the installation starts with a set of enabled features
- **THEN** the permission catalogue, settings registry, recognised stream topics and agent tool catalogue each contain exactly the entries those features declare

#### Scenario: Two features cannot claim the same contribution

- **WHEN** two features declare the same permission, setting key, topic or tool name
- **THEN** the build fails verification before the installation can be packaged

### Requirement: A feature built against another contract version is refused

The system SHALL define one contract version. A feature declaring a different version SHALL cause the build to fail verification, and SHALL never be loaded into a running installation.

#### Scenario: Mismatched contract version fails the build

- **WHEN** a feature declares a contract version other than the installation's
- **THEN** verification fails and names the feature and both versions

### Requirement: Optional features can be disabled at startup

The system SHALL enable each feature unless the startup property `artemis-studio.features.<id>.enabled` is `false`. The system SHALL identify a set of required modules: the kernel, broker connectivity, cluster registration and scraping. A required module SHALL NOT be disableable. Enablement SHALL be fixed for the life of the process.

#### Scenario: A feature is enabled by default

- **WHEN** no enablement property is set for a feature
- **THEN** the feature is enabled

#### Scenario: Disabling a required module is refused

- **WHEN** the enablement property for a required module is set to `false`
- **THEN** startup fails with a message naming the module and stating that it cannot be disabled

#### Scenario: Disabling a feature another enabled feature requires is refused

- **WHEN** a feature is disabled while an enabled feature declares that it requires it
- **THEN** startup fails with a message naming both features

### Requirement: A disabled feature is absent from every surface

When a feature is disabled, the system SHALL NOT serve its endpoints, SHALL NOT run its background jobs, and SHALL NOT offer its agent tools, stream topics, settings or permissions in their catalogues. The database schema SHALL remain unchanged, so re-enabling the feature needs no migration.

A request to an endpoint belonging to a disabled feature SHALL receive a `404` problem detail whose type identifies the feature as disabled and names the property that enables it.

#### Scenario: A disabled feature's endpoint explains itself

- **WHEN** a client calls an endpoint of a disabled feature
- **THEN** the response is `404` with a problem detail stating that the feature is disabled on this installation and naming `artemis-studio.features.<id>.enabled`

#### Scenario: A disabled feature runs nothing in the background

- **WHEN** a feature is disabled
- **THEN** none of its scheduled jobs run and none of its broker subscriptions are opened

#### Scenario: Re-enabling needs no migration

- **WHEN** a disabled feature is enabled and the installation restarts
- **THEN** the feature works against the existing database with no schema change

### Requirement: The installation publishes a manifest

The system SHALL expose an authenticated read of the installation's manifest. The manifest SHALL contain the contract version and, for every feature built into the installation, its identifier, title, whether it is enabled, and the permissions and topics it declares. It SHALL also contain the permission catalogue with the feature that owns each permission, and the configured identity providers. The manifest SHALL describe what the installation offers and SHALL NOT be treated as an authorization decision: every endpoint enforces permissions regardless of what a client derived from the manifest.

#### Scenario: The manifest lists enabled and disabled features

- **WHEN** an authenticated client reads the manifest on an installation with one feature disabled
- **THEN** every built-in feature appears, the disabled one is marked not enabled, and its permissions are absent from the permission catalogue

#### Scenario: The manifest requires authentication

- **WHEN** an unauthenticated client requests the manifest
- **THEN** the response is `401` with a problem detail body

#### Scenario: A client cannot gain access through the manifest

- **WHEN** a client calls an enabled feature's endpoint without the permission that endpoint requires
- **THEN** the request is refused exactly as it would be had the client never read the manifest
