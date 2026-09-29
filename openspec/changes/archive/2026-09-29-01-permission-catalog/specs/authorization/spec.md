## MODIFIED Requirements

### Requirement: Permissions are named strings grouped into roles

The system SHALL represent a permission as a string naming a resource and an action, and SHALL group permissions into named roles. A role SHALL support a wildcard permission that grants every action, and a wildcard scoped to one resource that grants every action on that resource. The system SHALL provide a read of every permission string the application checks, for use when building a role. That read SHALL be assembled from one catalogue: the permissions the installation's enabled features declare followed by those every active plugin declares. Each entry SHALL be attributed to the feature or plugin that declares it. Every reader of the catalogue, including the role editor, the API-key picker and the feature manifest, SHALL use that same catalogue.

A role MAY hold a permission of a feature that is currently disabled, or of a plugin that is inactive or removed. Holding it SHALL grant nothing while its owner is disabled or inactive, and SHALL take effect unchanged if the owner is enabled or activated again.

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

#### Scenario: A plugin's permissions can be granted

- **WHEN** a plugin that declares permissions is active and an administrator reads the catalogue
- **THEN** each of the plugin's permissions appears attributed to the plugin, and a role saved with them holds them

#### Scenario: A plugin activated while the editor is open

- **WHEN** a plugin is activated while an administrator has the role editor open, and the administrator reloads the editor
- **THEN** the plugin's permissions are offered

#### Scenario: A removed plugin's permissions are no longer offered

- **WHEN** a plugin is deactivated or removed
- **THEN** the catalogue no longer lists its permissions, and roles that hold them keep them unchanged

## ADDED Requirements

### Requirement: A permission has an owner, a description and the scopes at which it takes effect

Every catalogue entry SHALL state its owning module or plugin, a human description, and whether it takes effect at every scope or only at global scope. A plugin declares a global-only permission with `globalOnly: true` in its manifest; the default is every scope. This is descriptive: it SHALL NOT change how a role is granted at a global, environment or cluster scope.

#### Scenario: An entry lacks a description

- **WHEN** a registered permission has no description
- **THEN** the permission consistency check reports it

#### Scenario: A global-only permission granted at cluster scope

- **WHEN** a role holding a permission that acts only at global scope is granted to a user on one cluster
- **THEN** the effective-permissions preview marks that permission as having no effect at that scope, and the role editor marks the permission as global only

### Requirement: Effective permissions can be previewed for a user

Studio SHALL compute and return the effective permissions of a chosen user: for each of the user's role grants, every permission the role holds at the grant's scope and the role it comes from. A wildcard SHALL be expanded to the catalogue entries it matches, naming the wildcard it came through. The read SHALL require the permission to administer users.

#### Scenario: Preview for a user

- **WHEN** an authorised administrator previews a user
- **THEN** the result lists each effective permission with its scope and source role

#### Scenario: A wildcard is expanded

- **WHEN** the user holds a role with a resource-scoped wildcard
- **THEN** the preview lists each catalogue permission of that resource, each naming the wildcard as its source

#### Scenario: Preview without permission

- **WHEN** a caller lacks the permission to administer users
- **THEN** the preview is refused and reveals nothing about the user, not even whether the user exists

### Requirement: Two roles can be compared

Studio SHALL show the permissions that differ between two roles, marking which role holds each.

#### Scenario: Diff of two roles

- **WHEN** two roles are compared
- **THEN** permissions held by only one are listed against that role, and permissions held by both are not listed
