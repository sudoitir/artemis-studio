## ADDED Requirements

### Requirement: Secret provider and rotation state are visible to administrators
Studio SHALL show the active secret provider, the current key version and the last rotation result, and SHALL never show key material.

#### Scenario: Status view
- **WHEN** an administrator opens the security settings
- **THEN** provider, key version and last rotation are shown and no key is
