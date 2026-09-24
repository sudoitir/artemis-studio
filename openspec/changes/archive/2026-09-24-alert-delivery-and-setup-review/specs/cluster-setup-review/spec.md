## Purpose

Reviews a cluster's high-availability, clustering, durability and message-safety
configuration for known mistakes, such as a single replication pair that cannot win a
quorum vote. Each finding states what is wrong, what it costs, the evidence per node,
and the `broker.xml` that fixes it. An operator can accept a finding as a known risk.

## ADDED Requirements

### Requirement: A review reads each node's configuration in one batched call

The system SHALL review a cluster by reading, from each manageable node, the broker's
configuration attributes, the default address settings, and its cluster connections,
in one batched management request per node, subject to the node's rate limit. A
review SHALL make no change to any broker.

#### Scenario: One request per node

- **WHEN** a cluster of four manageable nodes is reviewed
- **THEN** exactly four management requests are made, one per node

### Requirement: A review runs on an interval and on demand

The system SHALL review every registered cluster on a configurable interval,
defaulting to fifteen minutes. An operator SHALL be able to run a review of one
cluster immediately. A request made sooner than a configurable minimum spacing after
the previous run SHALL be refused, with the current review returned and the reason
stated. Only one instance of the system SHALL review a given cluster at a time.

#### Scenario: A run on demand

- **WHEN** an operator runs a review of a cluster
- **THEN** the review is performed now, and the result shows the time it ran

#### Scenario: A run too soon is refused

- **WHEN** an operator runs a review seconds after the previous one
- **THEN** the request is refused with the reason, and the previous result is shown

### Requirement: A finding states what is wrong, what it costs, the evidence and the fix

Each finding SHALL carry:

- a stable code;
- a category: high availability, clustering, durability, message safety or security;
- a severity: critical, warning or info;
- a title and the impact, stated in operator terms;
- the evidence: the node and the configuration value observed there;
- a recommendation;
- where one exists, the `broker.xml` fragment that resolves it.

Severity SHALL NOT be conveyed by colour alone.

- **Critical** SHALL mean that data can be lost, duplicated or diverge under a
  foreseeable event.
- **Warning** SHALL mean that messages can be stranded or service degraded.
- **Info** SHALL mean a hardening step.

#### Scenario: The evidence names the node

- **WHEN** one node of three has persistence disabled
- **THEN** the finding names that node, and the value it reported

### Requirement: A single replication pair on quorum voting is a critical finding

When a cluster uses replication with quorum voting and has exactly one primary, the
system SHALL report a critical finding. The finding SHALL state that the backup will
promote itself whenever it loses its primary, without a vote, and so will split the
brain on a network partition. It SHALL recommend a distributed lock manager, or at
least three primary/backup pairs. It SHALL state that a network-check (pinger)
configuration mitigates the risk, and that the management API does not reveal whether
one is configured. With exactly two primaries, the system SHALL report a warning that
one surviving primary decides every vote. The count of primaries SHALL combine the
nodes Studio knows with the members reported by each node's cluster connection, and
SHALL state both.

#### Scenario: The dev pair

- **WHEN** a cluster is one primary and one backup, both reporting replication with
  quorum voting
- **THEN** a critical single-pair finding is reported, with the recommendation and
  the network-check caveat

#### Scenario: A lock manager is not flagged

- **WHEN** a single pair reports replication with a lock manager
- **THEN** no single-pair quorum finding is reported

#### Scenario: Three pairs are not flagged

- **WHEN** a cluster has three primaries, each with a backup, on quorum voting
- **THEN** no quorum finding is reported

### Requirement: Pair and policy mistakes are findings

The system SHALL report:

- a **warning** for a primary whose HA policy expects a backup when no backup shares
  its node identity;
- a **critical** finding for two endpoints of one node identity whose HA policies are
  incompatible (replication with shared store, a lock manager with quorum voting, or
  two primaries or two backups);
- an **info** finding for a node with no HA in a cluster of two or more nodes.

#### Scenario: Mismatched pair

- **WHEN** a primary reports replication and its backup reports shared store
- **THEN** a critical policy-mismatch finding names both endpoints and their policies

### Requirement: Clustering mistakes are findings

When a cluster has two or more nodes, the system SHALL report a finding for each of
the following:

- a node that is not clustered, or has no cluster connection;
- a cluster connection that is not started;
- a live node whose cluster connection does not see another node that Studio sees
  live;
- a clustered node whose connectors advertise a loopback or wildcard host;
- message load balancing turned off;
- a maximum of zero hops;
- load balancing that relies on redistribution while the default redistribution delay
  disables it, which leaves messages stranded on a node with no consumer;
- nodes running different broker versions;
- a cluster connection without duplicate detection, as info.

#### Scenario: Redistribution left at its default

- **WHEN** a clustered node load-balances on demand and the default address setting's
  redistribution delay is `-1`
- **THEN** a warning states that messages will be stranded on a node whose consumers
  leave, and offers `redistribution-delay` of `0`

#### Scenario: A member is not seen

- **WHEN** Studio sees nodes A, B and C live but node A's cluster connection lists
  only B
- **THEN** a warning on node A names C as unseen

### Requirement: Durability, message-safety and security mistakes are findings

The system SHALL report:

- a **critical** finding for disabled persistence;
- a **warning** for unbounded disk usage;
- a **warning** for a default address setting with no dead-letter address and a
  finite number of delivery attempts (messages are dropped);
- a **warning** for unlimited redelivery;
- an **info** finding for no expiry address;
- a **warning** for an address-full policy of `DROP`;
- a **warning** for disabled security;
- an **info** finding for an acceptor without TLS.

#### Scenario: No dead-letter address

- **WHEN** the default address setting has no dead-letter address and ten delivery
  attempts
- **THEN** a warning states that a message failing ten times is dropped, and offers a
  dead-letter address fragment

### Requirement: A review is honest about what it could not see

A node that did not answer SHALL be listed as not reviewed, with the reason. Its
earlier findings SHALL be kept and marked as not re-checked, never silently resolved
or presented as healthy. A cluster-wide rule SHALL be evaluated only when every live,
manageable node answered. A rule whose input a node did not report SHALL be listed as
not assessed for that node, with the reason. A configuration the management API does
not expose SHALL be named as not visible wherever a finding depends on it.

#### Scenario: One node unreachable

- **WHEN** one of three nodes does not answer a review
- **THEN** the review says two of three nodes were reviewed, names the third and why,
  and keeps its previous findings marked as not re-checked

### Requirement: A finding can be accepted as a known risk

An operator holding alert write permission SHALL be able to accept a finding as a
known risk, with a required reason and an optional expiry. An accepted finding SHALL
remain visible, marked as accepted, with who accepted it, when, why and until when. It
SHALL NOT raise a setup-risk alert until its acceptance expires or is revoked.
Accepting a risk and revoking an acceptance SHALL each be audited.

#### Scenario: A dev cluster's single pair is accepted

- **WHEN** an operator accepts the single-pair finding on a development cluster, with
  the reason "dev only"
- **THEN** the finding shows as accepted, with the reason and the operator, and no
  setup-risk alert fires for it

#### Scenario: An expired acceptance

- **WHEN** an acceptance's expiry passes and the finding is still produced
- **THEN** the finding is shown as open again, and can alert

### Requirement: The setup review is available to an AI assistant

The system SHALL expose the latest setup review of a cluster as a read-only MCP tool,
under the same permissions as the REST API.

#### Scenario: An assistant asks for the review

- **WHEN** an assistant with read permission on a cluster calls the tool
- **THEN** it receives the findings with severity, evidence and recommendation, and
  the list of nodes that were not reviewed
