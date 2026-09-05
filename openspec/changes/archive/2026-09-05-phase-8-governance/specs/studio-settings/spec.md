## ADDED Requirements

### Requirement: Settings writes require a global write permission

Reading operational settings SHALL require an authenticated principal.
Updating a setting, including the bulk-operation safety cap, SHALL require a
global settings-write permission.

#### Scenario: Settings write requires global permission

- **WHEN** a user without global settings-write permission attempts to change
  a setting
- **THEN** the request is rejected

#### Scenario: The bulk safety cap cannot be raised without permission

- **WHEN** a user without global settings-write permission attempts to raise
  the bulk-operation safety cap
- **THEN** the request is rejected and the previous cap remains in effect
