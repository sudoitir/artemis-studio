# Spec Delta: cluster-registration

## MODIFIED Requirements

### Requirement: A cluster is registered from one or more seed URLs

The system SHALL accept a list of seed Jolokia base URLs when registering a cluster, and one
seed SHALL be enough to register a whole cluster. A seed whose host name resolves to more than
one address SHALL be expanded into one seed per address, up to 16, keeping the seed's scheme, port
and path. The system SHALL probe each reachable seed, run the capability probe, discover the rest of
the cluster's topology, derive and prove a management URL for each discovered node, and persist
the cluster, its nodes, its credentials, its management URL pattern and any TLS reference.

A cluster has two cluster-wide accounts: a management account, used for every Jolokia call to
every node, and an optional Core account, stored as a distinct sealed secret. When the Core
account is omitted, the cluster's Core connections SHALL use the management account.

#### Scenario: One seed registers the whole cluster
- **WHEN** a cluster of two HA pairs is registered with one seed URL, and every broker serves management on the seed's scheme, port and path
- **THEN** all four brokers are persisted as manageable, matched to their `NodeID`s, with no other URL supplied

#### Scenario: Single reachable seed
- **WHEN** a cluster is registered with one seed URL that resolves to a live broker
- **THEN** the cluster is persisted with that node manageable, and every pair member found from topology is manageable when its derived URL answers with its `NodeID`

#### Scenario: A seed host with several addresses
- **WHEN** a cluster is registered with one seed whose host name resolves to three addresses
- **THEN** each address is probed as a seed with the seed's scheme, port and path

#### Scenario: Multiple seeds for an internal-hostname cluster
- **WHEN** a cluster is registered with two seed URLs, each reachable, whose brokers advertise internal connector hostnames to each other
- **THEN** both nodes are persisted as manageable, matched to their `NodeID`s

#### Scenario: No seed reachable
- **WHEN** every supplied seed URL fails to connect
- **THEN** registration fails with the classified connection error and nothing is persisted

#### Scenario: Separate Core credential is stored
- **WHEN** a cluster is registered with both a management and a Core account
- **THEN** both are stored as distinct sealed secrets and Core connections use the Core account

#### Scenario: Core credential defaults to the Jolokia credential
- **WHEN** a cluster is registered with only a management account
- **THEN** Core connections for that cluster authenticate with the management account

### Requirement: Dry-run registration persists nothing

When registration is requested with `dryRun` true, the system SHALL perform the probe, topology
discovery and management-URL derivation, and SHALL NOT create a cluster, node, credential, TLS,
revision or any other row. The registration form states what the check found as a table with one
row per node, and does not draw the topology: the graph appears on the cluster's Topology page
once it is registered. The discovered topology SHALL be returned in the same structural shape
used to render a registered cluster's topology graph.

Each node's row SHALL report: the node's name, HA role, `NodeID` and broker version; its
management URL and where that URL came from (a seed, derived from the pattern, or not found,
with the reason); whether the management account was accepted; and whether the Core account was
accepted on that node's Core address. Each account SHALL be reported on its own, so a rejected
Core account never hides an accepted management account or the reverse. The preview SHALL also
carry what adopting the running configuration would declare (see "Registration adopts the
running configuration as its first revision").

#### Scenario: Dry run returns a preview
- **WHEN** registration is called with `dryRun=true` against a live broker
- **THEN** the response contains capabilities, the discovered topology and one row per node
- **AND** no cluster row exists afterward

#### Scenario: Dry run still audited
- **WHEN** a dry-run registration is called
- **THEN** an audit event is written recording the attempt with its dry-run flag set

#### Scenario: Core account rejected while management works
- **WHEN** a dry-run uses a management account the brokers accept and a Core account they reject
- **THEN** each node reports management accepted and Core rejected, as two separate results

#### Scenario: A discovered node without a working URL
- **WHEN** a dry-run discovers a node whose derived management URL does not answer
- **THEN** that node is listed as found but not manageable, with the reason

#### Scenario: The check shows what could be configured after registering
- **WHEN** a dry-run registration reaches a broker with a capability gap Studio could close over the management API
- **THEN** the preview carries the same recommendations the registered cluster would show, seeded from the node the check reached, presented as a preview that cannot yet be declared
- **AND** registering then lands the operator on that cluster's recommended configuration, where the same panel can be declared and applied

#### Scenario: Preview topology renders like a saved cluster's
- **WHEN** the registration UI receives a dry-run preview
- **THEN** it lists the nodes it found in a table, without drawing a graph
- **AND** once the cluster is registered, its Topology page draws the discovered nodes with the same graph presentation as every registered cluster

## ADDED Requirements

### Requirement: Registration adopts the running configuration as its first revision

Registration SHALL offer to adopt the cluster's running configuration as its first declared
revision. The dry-run SHALL show what it would declare, as counts per section, and which nodes
disagree. The offer SHALL be on by default when the live nodes agree, and off by default when
they disagree, listing each disagreement. When registration is confirmed with the offer on, the
cluster and the adopted revision SHALL be saved together, attributed to the registering
operator, with source `ADOPTED`, and audited with the registration. Adoption SHALL write nothing
to a broker. After registration the system SHALL NOT adopt on its own.

#### Scenario: Agreeing nodes are adopted at registration
- **WHEN** an operator registers a cluster whose live nodes agree, leaving the adoption on
- **THEN** the cluster is saved with revision 1, source `ADOPTED`, attributed to that operator, and the cluster shows no drift

#### Scenario: Disagreeing nodes are not adopted by default
- **WHEN** the dry-run finds two nodes reporting different values for one key
- **THEN** the adoption is off by default and the disagreement is listed with both nodes and both values

#### Scenario: Declining the adoption
- **WHEN** an operator turns the adoption off and registers
- **THEN** no revision is saved and the configuration view offers adoption as before

### Requirement: A cluster's connection can be edited after registration

An operator with the right to edit the cluster SHALL be able to change its name, description,
seeds, management URL pattern, TLS bundle, management account and Core account
after registration, in one request. Every change SHALL be checkable as a dry-run that runs the
same per-node probes as a registration dry-run and persists nothing. A confirmed change SHALL be
stored and audited in one transaction with secrets redacted, SHALL never return a secret, and
SHALL trigger discovery at once. An omitted secret SHALL keep the stored one, but a stored secret
SHALL never be sent to a host the cluster does not already use: a change to the seeds, the management
URL pattern, the TLS bundle or an account's username SHALL be refused unless that account's password is
entered again. A management URL pattern SHALL have the shape `http(s)://{host}[:port][/path]`, with
`{host}` as the whole host, and an account written into a seed URL SHALL be refused.

#### Scenario: New credentials are checked before they are saved
- **WHEN** an operator enters a new management password and checks it
- **THEN** each node reports whether it accepts the password, and nothing is stored

#### Scenario: Saving a changed pattern rediscovers
- **WHEN** an operator saves a new management URL pattern
- **THEN** the change is audited, and every node whose URL was derived is probed on the new pattern at once

#### Scenario: A new host needs the password again
- **WHEN** an operator changes a seed to a host the cluster does not use and leaves the password empty
- **THEN** the check and the save are refused with "Enter the management password again to check new hosts", and nothing is sent to that host

#### Scenario: A pattern that moves the host is refused
- **WHEN** an operator enters the pattern `http://other.example/?h={host}`
- **THEN** it is refused, because `{host}` must be the whole host of the URL

#### Scenario: A secret left empty is kept
- **WHEN** an operator changes the name and leaves both password fields empty
- **THEN** both stored credentials are unchanged
