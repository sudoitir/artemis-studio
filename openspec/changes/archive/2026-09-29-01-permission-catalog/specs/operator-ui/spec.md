## ADDED Requirements

### Requirement: The role editor's permission picker is grouped, searchable and explained

The picker SHALL group permissions by module or plugin. It SHALL filter them by a search box, and show each permission's description and whether it acts only at global scope. It SHALL offer select-all and clear for each group. A permission the role holds that is not in the catalogue SHALL stay selected and be shown as not in the catalogue, so saving never drops it silently.

#### Scenario: Search narrows the list

- **WHEN** the operator types in the search box
- **THEN** only permissions matching name or description remain, groups with no match are hidden

#### Scenario: Search matches nothing

- **WHEN** the search matches no permission
- **THEN** the picker says that nothing matches and offers to clear the search

#### Scenario: Bulk select a group

- **WHEN** the operator selects a group
- **THEN** every permission in that group is granted in the draft and the count is announced

#### Scenario: A held permission is not in the catalogue

- **WHEN** a role holds a permission of an inactive plugin and the operator edits it
- **THEN** that permission is shown selected under "Not in the catalogue" and is kept on save unless the operator clears it

#### Scenario: Keyboard use

- **WHEN** the operator uses only the keyboard
- **THEN** every group, chip and checkbox is reachable and operable

### Requirement: An administrator can preview a user's effective permissions and compare two roles

The users view SHALL offer each user's effective permissions, grouped by scope, with the source role and the wildcard each one came through. A permission that has no effect at its scope SHALL be marked in words. The roles view SHALL offer a comparison of two roles, listing the permissions only one of them holds.

#### Scenario: Preview a user

- **WHEN** the administrator opens a user's effective permissions
- **THEN** each permission is listed under its scope with its source role, and a global-only permission held at cluster scope reads "No effect at this scope"

#### Scenario: Compare two roles

- **WHEN** the administrator picks two roles to compare
- **THEN** the permissions held only by the first and only by the second are listed in two labelled columns, and identical roles say they hold the same permissions
