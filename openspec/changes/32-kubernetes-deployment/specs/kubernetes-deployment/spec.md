## ADDED Requirements

### Requirement: The chart installs Studio with hardened defaults
The chart SHALL by default run as non-root with a read-only root filesystem, drop capabilities, set resource requests, define probes, a NetworkPolicy and a PodDisruptionBudget, and store no secret in plain values.

#### Scenario: Default install
- **WHEN** the chart is installed with defaults
- **THEN** the pods run non-root with a read-only filesystem and the policies exist

#### Scenario: A default is weakened
- **WHEN** a user overrides a hardening default
- **THEN** the chart accepts it and the documentation says what is given up

### Requirement: The chart supports the high-availability deployment
The chart SHALL run two or more replicas with the probes and shutdown behaviour of the HA deployment, against an external database.

#### Scenario: Replicas
- **WHEN** replicas is set to two
- **THEN** both serve traffic and one can be removed without dropping sessions

### Requirement: The chart is released with Studio and verified
The chart SHALL be versioned and published with each Studio release and be linted and installed in a test cluster in CI.

#### Scenario: Release
- **WHEN** a release is made
- **THEN** a chart of the same version is published

#### Scenario: Invalid chart
- **WHEN** a change breaks the chart
- **THEN** CI fails

### Requirement: Brokers managed by the operator are discovered
Studio SHALL discover brokers managed by the ArkMQ operator in the namespaces it is allowed to read and offer them for registration, without registering any automatically unless configured.

#### Scenario: A broker appears
- **WHEN** an operator-managed broker exists
- **THEN** it is offered for registration with its connection details

#### Scenario: No access
- **WHEN** Studio has no rights in a namespace
- **THEN** that namespace is not scanned and nothing else is affected

### Requirement: Clusters can be registered declaratively
A custom resource SHALL let a cluster be registered, updated and removed declaratively, with the outcome reported in its status. Credentials SHALL be referenced from Secrets, never inlined.

#### Scenario: A resource is created
- **WHEN** a valid resource is applied
- **THEN** the cluster is registered and the status shows it connected

#### Scenario: A resource is invalid
- **WHEN** it references a missing Secret
- **THEN** registration fails and the status says why

#### Scenario: A resource is deleted
- **WHEN** the resource is removed
- **THEN** the registration is removed and no broker data is touched

### Requirement: Deployment is documented
The documentation SHALL describe install, upgrade, values, RBAC, network policy and discovery for the chart.

#### Scenario: A new user follows the guide
- **WHEN** they follow it on a fresh cluster
- **THEN** Studio is reachable with a registered cluster
