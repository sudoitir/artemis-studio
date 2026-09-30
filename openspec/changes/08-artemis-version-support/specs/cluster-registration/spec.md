## ADDED Requirements

### Requirement: The broker version is detected on registration
Studio SHALL read the broker version of each seed when a cluster is registered, store each node's version, and
show it with where it sits against the supported range.

#### Scenario: Registration
- **WHEN** a cluster is registered
- **THEN** each node's version is recorded and visible

#### Scenario: A broker is upgraded
- **WHEN** a node's broker is upgraded
- **THEN** its recorded version is updated by the next poll, and its range status and any version gate follow the new version

#### Scenario: A node discovered later
- **WHEN** topology discovery adds a node running a version below the minimum
- **THEN** the node is shown with a warning that its release is unsupported, and the cluster stays registered

#### Scenario: No management URL
- **WHEN** a node is known only through the Core protocol and has no management URL
- **THEN** its version is unknown and it is never refused on that account

### Requirement: A broker outside the supported range is warned about or refused
When a seed's version is below the minimum, registration SHALL be refused with the reason, naming the minimum
and the seed's version, and nothing SHALL be stored. When it is above the latest tested version, registration
SHALL succeed with a visible warning.

#### Scenario: Too old
- **WHEN** a seed's version is below the minimum
- **THEN** registration is refused naming the minimum, and the refusal is audited

#### Scenario: Newer than tested
- **WHEN** a node's version is above the latest tested
- **THEN** registration succeeds, the connection check warns that the release is untested, and the node is marked as newer than tested

#### Scenario: Dry run
- **WHEN** registration is a dry run
- **THEN** the same verdict is reported and nothing is stored
