## MODIFIED Requirements

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

## ADDED Requirements

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
