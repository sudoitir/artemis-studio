## ADDED Requirements

### Requirement: An alert rule can be about the installation rather than a cluster
An alert rule SHALL be either cluster-scoped or installation-scoped. An installation-scoped rule SHALL
evaluate a condition of the Studio installation itself, such as storage quotas and storage health,
once per installation, and SHALL otherwise behave like any rule: debounce, history, channels, editing,
disabling. Studio SHALL seed one `STORAGE_QUOTA` and one `STORAGE_HEALTH` rule per installation,
enabled and bound to no channel.

#### Scenario: Seeded once
- **WHEN** Studio starts on a new installation, with or without registered clusters
- **THEN** exactly one `STORAGE_QUOTA` and one `STORAGE_HEALTH` installation rule exist

#### Scenario: Fires once, not per cluster
- **WHEN** a store crosses its quota warning on an installation with three clusters
- **THEN** one alert fires, shown as belonging to the installation

#### Scenario: Can be silenced
- **WHEN** an operator disables the seeded `STORAGE_HEALTH` rule
- **THEN** it no longer fires until re-enabled
