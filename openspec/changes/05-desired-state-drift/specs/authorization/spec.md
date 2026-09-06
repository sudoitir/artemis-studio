## ADDED Requirements

### Requirement: Declaring expected state is a distinct authority from changing brokers

The system SHALL define a permission for creating and editing a cluster's declared
expected state, resolvable at global, environment, or cluster scope through the
existing scope walk, and listed in the permission catalogue with a human label.

This permission SHALL NOT confer the ability to change any broker. Applying a fix
for a drift finding SHALL require the permission for the lifecycle operation the fix
performs.

#### Scenario: Declaring without changing

- **WHEN** a role holds the declaration permission and no lifecycle permission
- **THEN** its holder can record and edit what a cluster should contain and cannot
  apply any fix

#### Scenario: Changing without declaring

- **WHEN** a role holds lifecycle permissions and not the declaration permission
- **THEN** its holder can apply fixes offered by the drift report and cannot edit
  the declaration
