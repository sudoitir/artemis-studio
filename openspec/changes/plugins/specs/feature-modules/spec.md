## MODIFIED Requirements

### Requirement: The installation publishes a manifest

The system SHALL expose an authenticated read of the installation's manifest. It SHALL contain:
- the contract version;
- for every built-in feature and every installed plugin: its identifier, its title, its origin (built-in or plugin), whether it is enabled, and the permissions and topics it declares;
- for every plugin, additionally: its version, its vendor, its status, and where its UI entry is served;
- the permission catalogue, with the feature that owns each permission;
- the configured identity providers;
- a version value that changes whenever the set of active plugins changes.

The manifest SHALL describe what the installation offers and SHALL NOT be treated as an authorization decision: every endpoint enforces permissions regardless of what a client derived from the manifest.

#### Scenario: The manifest lists enabled and disabled features

- **WHEN** an authenticated client reads the manifest on an installation with one feature disabled
- **THEN** every built-in feature appears, the disabled one is marked not enabled, and its permissions are absent from the permission catalogue

#### Scenario: The manifest lists installed plugins with their status

- **WHEN** an authenticated client reads the manifest on an installation with one active plugin and one failed plugin
- **THEN** both appear with origin plugin, their versions and statuses, only the active one's permissions appear in the catalogue, and only the active one carries a UI entry

#### Scenario: The manifest version changes with the plugin set

- **WHEN** a plugin is activated or disabled
- **THEN** the manifest's version value differs from the value read before the change

#### Scenario: The manifest requires authentication

- **WHEN** an unauthenticated client requests the manifest
- **THEN** the response is `401` with a problem detail body

#### Scenario: A client cannot gain access through the manifest

- **WHEN** a client calls an enabled feature's endpoint without the permission that endpoint requires
- **THEN** the request is refused exactly as it would be had the client never read the manifest

## ADDED Requirements

### Requirement: A feature can be a runtime plugin

A feature SHALL be either built into the installation or installed at runtime as a plugin. A plugin SHALL declare the same kinds of contribution as a built-in feature, and its contributions SHALL join and leave the permission catalogue, settings registry, recognised stream topics and agent tool catalogue as it is activated and deactivated, without a restart. A plugin's identifier SHALL never equal a built-in feature's identifier, and SHALL never be required by a built-in feature.

#### Scenario: An activated plugin's contributions join the catalogues

- **WHEN** a plugin declaring a permission, a setting and a topic is activated
- **THEN** the permission catalogue, settings registry and recognised topics include them without a restart, attributed to the plugin

#### Scenario: A deactivated plugin's contributions leave the catalogues

- **WHEN** that plugin is disabled
- **THEN** its permission, setting and topic are no longer listed, and its endpoints answer `404` with problem type `feature-disabled`
