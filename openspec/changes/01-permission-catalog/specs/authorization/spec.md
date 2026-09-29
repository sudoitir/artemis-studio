## ADDED Requirements

### Requirement: The permission catalog lists every registered permission
The catalog served to the role editor SHALL contain every permission registered by Studio core and by every active plugin, and SHALL contain no permission from a plugin that is not active.

#### Scenario: A plugin's permissions are listed
- **WHEN** a plugin that declares permissions is active
- **THEN** each of its permissions appears in the catalog with its owner

#### Scenario: A removed plugin's permissions vanish
- **WHEN** a plugin is removed
- **THEN** its permissions no longer appear in the catalog

#### Scenario: A plugin added at runtime
- **WHEN** a plugin is installed while Studio runs
- **THEN** its permissions appear without a restart of the role editor session beyond a reload

### Requirement: A permission has an owner, a description and the scopes it can be granted at
Every catalog entry SHALL state its owning module or plugin, a human description and which of global, environment and cluster scope it accepts.

#### Scenario: An entry lacks a description
- **WHEN** a registered permission has no description
- **THEN** the consistency check reports it

### Requirement: Effective permissions can be previewed for a user
Studio SHALL compute and return the effective permissions of a chosen user, per scope, and the roles each one comes from, honouring the caller's own visibility.

#### Scenario: Preview for a user
- **WHEN** an authorised administrator previews a user
- **THEN** the result lists each effective permission with its scope and source role

#### Scenario: Preview without permission
- **WHEN** a caller lacks the permission to read role assignments
- **THEN** the preview is refused and reveals nothing about the user

### Requirement: Two roles can be compared
Studio SHALL return the permissions that differ between two roles, marking which role holds each.

#### Scenario: Diff of two roles
- **WHEN** two roles are compared
- **THEN** permissions held by only one are listed against that role
