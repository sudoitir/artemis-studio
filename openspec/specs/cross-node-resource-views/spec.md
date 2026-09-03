# cross-node-resource-views Specification

## Purpose
Defines how Artemis Studio presents a cluster's queues, addresses, consumers,
sessions, connections, and producers as one view across every logical node —
the merge key, how a live/backup pair is counted once, and how nodes that are
unmanageable or temporarily unreachable are represented rather than hidden.

## Requirements

### Requirement: A live/backup pair is one logical node

The system SHALL treat two endpoints sharing one `NodeID` as a single logical
node and SHALL read resource lists only from the serving endpoint of a pair, so
that a queue present on a live/backup pair is counted once, not twice.

#### Scenario: Backup endpoint is not enumerated

- **WHEN** a cluster has a live/backup pair and the queues view is requested
- **THEN** each queue on that pair appears once, attributed to the one logical node

### Requirement: Queues are aggregated across logical nodes

The system SHALL expose a cluster queues view whose rows are keyed by address,
queue name, and routing type, each row carrying per-node counters (message
count, consumer count, delivering count, scheduled count), a rolled-up total
across nodes, and how many of the cluster's logical nodes the queue is present
on.

#### Scenario: A queue on two nodes

- **WHEN** the same queue exists on two logical nodes with different depths
- **THEN** the row shows both per-node depths, their sum as the total, and a
  node-presence count of two

#### Scenario: A queue on one node only

- **WHEN** a queue exists on one logical node of a multi-node cluster
- **THEN** the row's node-presence count reflects one of the cluster's nodes,
  not an error or a zero for the others

### Requirement: Addresses, consumers, sessions, connections, and producers are listed live across nodes

The system SHALL expose, per cluster, a view for each of addresses, consumers,
sessions, connections, and producers, built by querying every serving endpoint
once, tagging each row with the logical node it came from, and returning the
merged, paginated result.

#### Scenario: Consumers from every node in one list

- **WHEN** the consumers view is requested for a multi-node cluster
- **THEN** the response contains consumers from every serving node, each tagged
  with its node, in one paginated list

#### Scenario: One batched request per node

- **WHEN** any of these views is loaded
- **THEN** Studio issues one batched request per serving node, not one request
  per row or per node per attribute

### Requirement: Unmanageable nodes are shown as present without data

The system SHALL represent a node that is known but has no reachable management
URL as present in the node dimension of every cross-node view, with no counters
rather than zeroed counters, and SHALL NOT omit it.

#### Scenario: Known-but-not-manageable node in the queues view

- **WHEN** a cluster has a discovered node with no management URL and the queues
  view is requested
- **THEN** that node appears in the view's node dimension marked as having no
  data, not as contributing zeros

### Requirement: A stale node's last data is marked, not dropped

When a serving endpoint is temporarily unreachable, the system SHALL continue to
show that node's most recent cached rows marked as stale with the time they were
last seen, and SHALL NOT delete them because one scrape failed.

#### Scenario: Live endpoint unreachable for one cycle

- **WHEN** a node's serving endpoint fails a scrape but succeeded previously
- **THEN** its rows remain in the queues view marked stale with a last-seen time

### Requirement: Cross-node views require management read access

When a cluster's connection does not have management read capability, the
system SHALL return an explanation and the `broker.xml` change that would grant
it rather than an empty result.

#### Scenario: Management read unavailable

- **WHEN** a cross-node view is requested for a cluster whose connection lacks
  management read
- **THEN** the response carries the capability reason and the enabling
  `broker.xml` snippet, and the UI shows those rather than an empty table
