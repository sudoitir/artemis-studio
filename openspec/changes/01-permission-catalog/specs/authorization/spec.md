## ADDED Requirements

### Requirement: The role editor offers every permission in the catalogue
The role editor SHALL offer exactly the permissions the catalogue holds, including those of every active plugin, and an end-to-end test with a real plugin SHALL prove it.

#### Scenario: A plugin's permissions can be granted
- **WHEN** a plugin that declares permissions is active and an administrator opens the role editor
- **THEN** each of its permissions is offered under the plugin's name and can be saved into a role

#### Scenario: A plugin activated while the editor is open
- **WHEN** a plugin is activated while an administrator has the role editor open, and the administrator reloads the editor
- **THEN** the plugin's permissions are offered

#### Scenario: A removed plugin's permissions are no longer offered
- **WHEN** a plugin is deactivated or removed
- **THEN** the role editor no longer offers its permissions, and roles that hold them keep them unchanged

### Requirement: A permission has an owner, a description and the scopes at which it takes effect
Every catalogue entry SHALL state its owning module or plugin, a human description, and whether it takes effect at every scope or only at global scope. This is descriptive: it SHALL NOT change how a role is granted at a global, environment or cluster scope.

#### Scenario: An entry lacks a description
- **WHEN** a registered permission has no description
- **THEN** the consistency check reports it

#### Scenario: A global-only permission granted at cluster scope
- **WHEN** a role holding a permission that acts only at global scope is granted to a user on one cluster
- **THEN** the role editor and the effective-permissions preview state that the permission has no effect at that scope

### Requirement: Effective permissions can be previewed for a user
Studio SHALL compute and return the effective permissions of a chosen user, per scope, and the roles each one comes from.

#### Scenario: Preview for a user
- **WHEN** an authorised administrator previews a user
- **THEN** the result lists each effective permission with its scope and source role

#### Scenario: Preview without permission
- **WHEN** a caller lacks the permission to read role assignments
- **THEN** the preview is refused and reveals nothing about the user, not even whether the user exists

### Requirement: Two roles can be compared
Studio SHALL return the permissions that differ between two roles, marking which role holds each.

#### Scenario: Diff of two roles
- **WHEN** two roles are compared
- **THEN** permissions held by only one are listed against that role, and permissions held by both are not listed
