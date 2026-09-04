## Purpose

Defines the Core protocol connection to a broker node — how its address and
credentials are resolved, and how the connection is opened, held, and torn down
— as the second broker transport alongside Jolokia.

## Requirements

### Requirement: A dialable Core URL is resolved per node

The system SHALL determine a Core connection URL for a node from, in order of
precedence, an operator-set manual Core URL, otherwise the Core connector
address learned from topology discovery. A bare `host:port` value SHALL be
normalised to a Core protocol URL. When no Core URL can be resolved for any
serving node of a cluster, the cluster has no Core transport and this SHALL be
reported as the reason its notification capability is unavailable.

#### Scenario: Manual override wins

- **WHEN** a node has both a discovered Core connector and an operator-set manual Core URL
- **THEN** the manual Core URL is used

#### Scenario: Discovered connector is used when there is no override

- **WHEN** a node has a discovered Core connector and no manual override
- **THEN** the discovered connector, normalised to a Core protocol URL, is used

#### Scenario: No resolvable URL disables the transport

- **WHEN** no serving node of a cluster has a resolvable Core URL
- **THEN** the cluster has no Core transport and its notification capability reports that as the reason

### Requirement: The Core credential defaults to the Jolokia credential

The system SHALL use a stored Core-kind credential for a cluster's Core
connections when one exists, and otherwise SHALL use that cluster's stored
Jolokia credential. A Core-kind credential SHALL be stored as a distinct sealed
secret, not derived from the Jolokia credential's stored form.

#### Scenario: Explicit Core credential is used

- **WHEN** a cluster has a stored Core-kind credential
- **THEN** Core connections for that cluster authenticate with it

#### Scenario: Falls back to the Jolokia credential

- **WHEN** a cluster has no stored Core-kind credential
- **THEN** Core connections for that cluster authenticate with the stored Jolokia credential

### Requirement: The Core connection is configured not to be driven by broker topology

The system SHALL open Core connections with client-side topology-driven load
balancing disabled and the client library's automatic reconnect disabled, so
that a connector hostname the broker advertises but Studio cannot resolve does
not cause a blocking retry. TLS settings for a Core connection SHALL be resolved
from the same per-cluster TLS reference used for the Jolokia connection.

#### Scenario: An unresolvable advertised connector does not wedge the connection

- **WHEN** the broker advertises a Core connector hostname that does not resolve from where Studio runs
- **THEN** the Core connection attempt fails fast and is retried by Studio rather than blocking on a library reconnect loop

#### Scenario: TLS reference is shared

- **WHEN** a cluster has a TLS reference configured
- **THEN** Core connections for that cluster use the same trust material as its Jolokia connection

### Requirement: Core connections are released on shutdown and cluster removal

The system SHALL pool Core connections per cluster rather than opening and
closing one per call, and SHALL close every pooled Core connection for a cluster
when that cluster is removed, and SHALL close every pooled Core connection on
application shutdown without letting a hung close delay shutdown beyond a
bounded wait.

#### Scenario: Removing a cluster closes its connections

- **WHEN** a cluster is removed
- **THEN** its Core subscriptions and pooled connections are closed and not reopened

#### Scenario: Shutdown is not blocked by a hung close

- **WHEN** the application shuts down and one Core connection does not close promptly
- **THEN** shutdown proceeds after a bounded wait

#### Scenario: Repeated calls to the same node reuse a pooled connection

- **WHEN** two consecutive Core operations target the same cluster and Core URL
- **THEN** the second operation reuses a pooled connection rather than opening a new one
