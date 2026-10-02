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
and SHALL NOT create a cluster, node, credential, TLS, or any other row. The
discovered topology SHALL be returned in the same structural shape used to render
a registered cluster's topology graph. The registration form states what the check
found in words (the node count) and does not draw the topology: the graph appears
on the cluster's Topology page once it is registered.

#### Scenario: Dry run returns a preview

- **WHEN** registration is called with `dryRun=true` against a live broker
- **THEN** the response contains capabilities and discovered topology
- **AND** no cluster row exists afterward

#### Scenario: Dry run still audited

- **WHEN** a dry-run registration is called
- **THEN** an audit event is written recording the attempt with its dry-run flag set

#### Scenario: The check shows what could be configured after registering

- **WHEN** a dry-run registration reaches a broker with a capability gap Studio
  could close over the management API
- **THEN** the preview carries the same recommendations the registered cluster
  would show, seeded from the node the check reached, presented as a preview that
  cannot yet be declared — there is no cluster for a revision to belong to
- **AND** registering then lands the operator on that cluster's recommended
  configuration, where the same panel can be declared and applied

#### Scenario: Preview topology renders like a saved cluster's

- **WHEN** the registration UI receives a dry-run preview
- **THEN** it states in words how many nodes the check found, without drawing a graph
- **AND** once the cluster is registered, its Topology page draws the discovered nodes with the same graph
  presentation as every registered cluster

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

In the frontend, removal SHALL be offered only from the cluster's settings, as the last section of the
cluster's own settings group, and SHALL NOT be offered from the header shown above the cluster's views.
The section SHALL state what removal deletes and that the broker is not touched before it can be armed.
A failed removal SHALL state its cause and leave the operator on the section; a successful removal SHALL
take the operator away from the removed cluster.

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

#### Scenario: Removal lives in settings

- **WHEN** an operator views any page of a cluster
- **THEN** the cluster header offers no remove action, and the cluster's settings contain a Remove
  cluster section

#### Scenario: A failed removal says why

- **WHEN** the removal request fails
- **THEN** the section states the cause and the next action, and the cluster remains registered

### Requirement: Cluster registration and listing require the matching permission

Registering a cluster SHALL require a global write permission. Listing,
inspecting, or removing a cluster SHALL require the corresponding read or
write permission resolved at that cluster's scope, as defined by the
authorization capability.

#### Scenario: Registration requires global write

- **WHEN** a user without global cluster-write permission attempts to
  register a cluster
- **THEN** the request is rejected

#### Scenario: Listing is scoped to granted clusters

- **WHEN** a user holding a grant on only some registered clusters lists
  clusters
- **THEN** only the clusters they hold a read grant on are returned

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

### Requirement: A cluster's brokers are registered only once

The system SHALL refuse a registration, and its dry run, when any broker it reaches or discovers already
belongs to a registered cluster. Two registrations are of the same brokers when a node of one reports a
NodeID a node of the other carries, whatever URL reached it; a seed whose broker reports no NodeID is
compared by its management URL in normal form (scheme and host in lower case, the port explicit, no
trailing slash). The refusal SHALL be a 409 problem of type `cluster-already-registered` whose detail names
the registered cluster and its overlapping nodes, with the cluster's id and name as `existingClusterId`
and `existingClusterName` and the nodes as `overlappingNodes`; when the caller may not read that cluster,
the refusal SHALL NOT name it, and neither SHALL its audit record. The refused attempt SHALL be audited as
a failure with the message the caller received, and nothing of it SHALL be stored. The refusal SHALL say
that a cloned or restored broker carries the same NodeID and needs a fresh journal; there is no override.
The database SHALL hold each broker's identity once, so that of two registrations of the same brokers
made at the same time exactly one succeeds and the other receives the same refusal. A registered
cluster's held identities SHALL follow its nodes: discovery, a changed NodeID and a management URL
override SHALL update them in the same transaction, and a held identity no node carries any more SHALL be
released rather than refuse a registration. The registration form SHALL show the refusal with a link to
the registered cluster and keep Register disabled.

#### Scenario: The same management URL

- **WHEN** a cluster is registered from a broker's management URL that a registered cluster already uses
- **THEN** the registration is refused with a 409 naming that cluster and its nodes
- **AND** no second cluster exists, and the refused attempt is audited as a failure

#### Scenario: Another URL of the same broker

- **WHEN** a registration reaches a registered broker through a different URL, such as its IP address
  instead of its host name, another port mapping, or a trailing slash
- **THEN** the broker's NodeID identifies it and the registration is refused naming the registered cluster

#### Scenario: A partial overlap

- **WHEN** a registration's seeds or discovered nodes include one broker of a registered cluster alongside
  brokers that are not registered
- **THEN** the registration is refused naming only the registered cluster's nodes that overlap
- **AND** none of the brokers is registered

#### Scenario: The dry run warns

- **WHEN** the connection check is run for brokers a registered cluster already holds
- **THEN** it answers the same 409 before anything is registered, the dry-run attempt is audited as a failure
- **AND** the form shows which cluster holds the brokers with a link to open it, and Register stays disabled

#### Scenario: Concurrent registrations

- **WHEN** two registrations of the same brokers run at the same time
- **THEN** exactly one cluster is registered
- **AND** the other is refused with the same 409 naming that cluster, not a server error

#### Scenario: A cluster the caller cannot see

- **WHEN** the brokers belong to a cluster the caller has no grant to read
- **THEN** the registration is refused with a 409 that names no cluster, no id and no nodes
- **AND** the audit record of the attempt names no cluster and no node either

#### Scenario: A management URL moved by an override is released

- **WHEN** a registered node known only by its management URL is given another URL by an override
- **THEN** its cluster holds the new URL and no longer holds the old one
- **AND** a broker that later answers at the old URL can be registered

#### Scenario: A held identity no node carries any more

- **WHEN** a cluster still holds a broker's identity but none of its nodes carries it, its node having
  moved or its broker's journal having been replaced
- **THEN** registering that broker succeeds, and the identity passes to the new cluster

#### Scenario: Nodes found after registration are held

- **WHEN** discovery finds a node that joined the cluster after it was registered, or a node reports a
  NodeID other than the stored one
- **THEN** the cluster holds that node's identity from then on

#### Scenario: A cloned broker

- **WHEN** a broker cloned or restored from a registered broker's data directory is registered
- **THEN** it is refused as the same broker, and the refusal says to give it a fresh journal
