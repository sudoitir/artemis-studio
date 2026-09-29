## ADDED Requirements

### Requirement: Permission declaration mismatches are reported

Studio SHALL compare the permissions that method guards name and that active plugin manifests reference against the permission catalogue, and SHALL report every mismatch in operational health without preventing startup or a plugin's activation. The comparison SHALL cover Studio's own guards from startup and each active plugin's guards and manifest from its activation until its deactivation. A registered permission without a description, and a global-only permission that a guard checks against a cluster, SHALL also be reported. Any mismatch SHALL make the report degraded.

#### Scenario: Guard uses an unregistered permission

- **WHEN** a guard names a permission that is not registered
- **THEN** operational health reports it as a mismatch naming the guarded method, and Studio still starts

#### Scenario: Declared but unregistered

- **WHEN** an active plugin's manifest references a permission that is not registered
- **THEN** operational health reports it naming the plugin

#### Scenario: A deactivated plugin leaves the report

- **WHEN** a plugin whose guards caused a mismatch is deactivated
- **THEN** its mismatches are no longer reported

#### Scenario: Everything matches

- **WHEN** all declared permissions are registered and described
- **THEN** the check reports healthy
