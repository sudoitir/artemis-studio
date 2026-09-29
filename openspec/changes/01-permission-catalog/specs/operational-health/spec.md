## ADDED Requirements

### Requirement: Permission declaration mismatches are reported
At startup Studio SHALL compare the permissions declared by plugin manifests and used in method guards against the registered permissions, and SHALL report every mismatch in operational health without preventing startup.

#### Scenario: Guard uses an unregistered permission
- **WHEN** a guard names a permission that is not registered
- **THEN** operational health reports it as a mismatch and Studio still starts

#### Scenario: Declared but unregistered
- **WHEN** a manifest declares a permission that is not registered
- **THEN** operational health reports it naming the plugin

#### Scenario: Everything matches
- **WHEN** all declared permissions are registered
- **THEN** the check reports healthy
