## Purpose

Defines the lifecycle of a cluster registration in Artemis Studio: creating one
from a set of reachable seed URLs, listing and inspecting registrations,
removing one, the dry-run contract that lets an operator probe before
committing, and the audit trail every mutation leaves.

## Requirements

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

### Requirement: Dry-run registration persists nothing

When registration is requested with `dryRun` true, the system SHALL perform the
probe and topology discovery and return the capabilities and discovered topology,
and SHALL NOT create a cluster, node, credential, TLS, or any other row.

#### Scenario: Dry run returns a preview

- **WHEN** registration is called with `dryRun=true` against a live broker
- **THEN** the response contains capabilities and discovered topology
- **AND** no cluster row exists afterward

#### Scenario: Dry run still audited

- **WHEN** a dry-run registration is called
- **THEN** an audit event is written recording the attempt with its dry-run flag set

### Requirement: Every mutating call is audited within its transaction

For each mutating endpoint the system SHALL write an audit event in the same
database transaction as the command. The event SHALL be recorded as pending
before the broker is contacted and updated to a success or failure outcome
afterward, and SHALL capture the action, target, affected count where
applicable, and dry-run flag.

#### Scenario: Successful registration

- **WHEN** a cluster is registered successfully
- **THEN** exactly one audit event exists for it, transitioning from pending to success

#### Scenario: Failed registration

- **WHEN** a registration fails after the audit row is created
- **THEN** the audit event is present with a failure outcome and an error detail

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

### Requirement: The unauthenticated API is flagged at startup

Until authentication exists, the system SHALL log a warning at startup whenever
the API is served without authentication and is not bound to a loopback
address, naming the exposure.

#### Scenario: Exposed and unauthenticated

- **WHEN** the app starts unauthenticated and bound to a non-loopback address
- **THEN** a startup WARN is logged describing the risk

#### Scenario: Loopback is quiet

- **WHEN** the app starts unauthenticated and bound only to loopback
- **THEN** no such warning is logged
