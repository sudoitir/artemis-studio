## MODIFIED Requirements

### Requirement: A cluster is registered from one or more seed URLs

The system SHALL accept a list of seed Jolokia base URLs when registering a
cluster. It SHALL probe each reachable seed, run the capability probe, discover
the rest of the cluster's topology, and persist the cluster, its nodes, its
credentials, and any TLS reference. Registration MAY additionally accept a Core
credential, stored as a distinct sealed secret; when omitted, the cluster's Core
connections SHALL use the Jolokia credential.

#### Scenario: Single reachable seed

- **WHEN** a cluster is registered with one seed URL that resolves to a live broker
- **THEN** the cluster is persisted with that node manageable and any pair member discovered from topology

#### Scenario: Multiple seeds for an internal-hostname cluster

- **WHEN** a cluster is registered with two seed URLs, each reachable, whose brokers advertise internal connector hostnames to each other
- **THEN** both nodes are persisted as manageable, matched to their `NodeID`s

#### Scenario: No seed reachable

- **WHEN** every supplied seed URL fails to connect
- **THEN** registration fails with the classified connection error and nothing is persisted

#### Scenario: Separate Core credential is stored

- **WHEN** a cluster is registered with both a Jolokia and a Core credential
- **THEN** both are stored as distinct sealed secrets and Core connections use the Core credential

#### Scenario: Core credential defaults to the Jolokia credential

- **WHEN** a cluster is registered with only a Jolokia credential
- **THEN** Core connections for that cluster authenticate with the Jolokia credential

### Requirement: Clusters can be listed, inspected, and removed

The system SHALL expose reading the list of registered clusters with a
rolled-up health indication, reading one cluster with its nodes, and removing a
cluster. Removal SHALL delete Studio's registration and stored credentials for
that cluster and SHALL NOT attempt any change on the broker itself. Removal
SHALL also close that cluster's Core connections and discard its in-memory
subscription state.

#### Scenario: List shows health

- **WHEN** the cluster list is requested
- **THEN** each entry carries a health summary derived from its nodes

#### Scenario: Removal is local only

- **WHEN** a cluster is removed
- **THEN** its rows and credentials are deleted and no broker operation is invoked

#### Scenario: Removal releases Core connections

- **WHEN** a cluster with an active Core subscription is removed
- **THEN** its Core connections are closed and not reopened

#### Scenario: Removal is guarded in the UI

- **WHEN** a user removes a cluster from the frontend
- **THEN** the UI requires the cluster name to be typed to confirm
