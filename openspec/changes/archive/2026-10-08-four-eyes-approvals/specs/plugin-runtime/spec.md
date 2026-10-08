## ADDED Requirements

### Requirement: A plugin SHALL declare itself an approval provider

A plugin SHALL declare in its descriptor that it is an approval provider, naming the permission its approvers need, which the plugin itself declares. At most one approval provider SHALL be active. The gate SHALL count as installed whenever the provider is meant to be active, even while it is starting or has failed, so that gated operations fail closed.

#### Scenario: Second provider

- **WHEN** an administrator activates a second approval provider
- **THEN** activation is refused with the reason

#### Scenario: Provider failed to start

- **WHEN** the approval provider failed to start and an operator purges a queue
- **THEN** the purge is not run and the operator is told approvals are unavailable
