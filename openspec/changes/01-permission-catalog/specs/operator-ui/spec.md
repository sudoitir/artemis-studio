## ADDED Requirements

### Requirement: The role editor's permission picker is grouped, searchable and explained
The picker SHALL group permissions by module or plugin, filter them by a search box, show each description and the scopes at which it takes effect, and offer select-all and clear per group.

#### Scenario: Search narrows the list
- **WHEN** the operator types in the search box
- **THEN** only permissions matching name or description remain, groups with no match are hidden

#### Scenario: Bulk select a group
- **WHEN** the operator selects a group
- **THEN** every permission in that group is granted in the draft and the count is announced

#### Scenario: Keyboard use
- **WHEN** the operator uses only the keyboard
- **THEN** every group, chip and checkbox is reachable and operable
