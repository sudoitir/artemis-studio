## MODIFIED Requirements

### Requirement: Permissions are named strings grouped into roles

The system SHALL represent a permission as a string naming a resource and an action, and SHALL group permissions into named roles. A role SHALL support a wildcard permission that grants every action, and a wildcard scoped to one resource that grants every action on that resource. The system SHALL provide a read of every permission string the application checks, for use when building a role. That read SHALL be assembled from the permissions the installation's enabled features declare, and each permission SHALL be attributed to the feature that declares it.

A role MAY hold a permission of a feature that is currently disabled. Holding it SHALL grant nothing while the feature is disabled, and SHALL take effect unchanged if the feature is enabled again.

#### Scenario: A role wildcard grants all actions on a resource

- **WHEN** a role holds a resource-scoped wildcard permission
- **THEN** every action on that resource is permitted for a user holding that role at the matching scope

#### Scenario: The full-access wildcard grants everything

- **WHEN** a role holds the full-access wildcard permission
- **THEN** every permission check for a user holding that role at the matching scope succeeds

#### Scenario: The catalogue lists only enabled features' permissions

- **WHEN** the permission catalogue is read on an installation with one feature disabled
- **THEN** every permission of the enabled features appears, attributed to its feature, and none of the disabled feature's permissions appear

#### Scenario: A role keeps a disabled feature's permission

- **WHEN** a feature is disabled and a custom role holds one of its permissions
- **THEN** the role is unchanged, and the permission is effective again once the feature is re-enabled
