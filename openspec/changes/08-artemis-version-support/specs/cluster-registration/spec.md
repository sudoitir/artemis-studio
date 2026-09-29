## ADDED Requirements

### Requirement: The broker version is detected on registration
Studio SHALL read the broker version of each node when a cluster is registered and store it.

#### Scenario: Registration
- **WHEN** a cluster is registered
- **THEN** each node's version is recorded and visible

#### Scenario: A broker is upgraded
- **WHEN** a node reconnects after its broker was upgraded
- **THEN** its recorded version is updated and capability gating follows the new version

#### Scenario: A node discovered later
- **WHEN** topology discovery adds a node running a version below the minimum
- **THEN** the node is shown with a warning that it is unsupported, and the cluster stays registered

### Requirement: A broker outside the supported range is warned about or refused
When a node's version is below the minimum, registration SHALL be refused with the reason; when it is above the latest tested version, registration SHALL succeed with a visible warning.

#### Scenario: Too old
- **WHEN** the version is below the minimum
- **THEN** registration is refused naming the minimum

#### Scenario: Newer than tested
- **WHEN** the version is above the latest tested
- **THEN** registration succeeds with a warning

#### Scenario: Dry run
- **WHEN** registration is a dry run
- **THEN** the same verdict is reported and nothing is stored
