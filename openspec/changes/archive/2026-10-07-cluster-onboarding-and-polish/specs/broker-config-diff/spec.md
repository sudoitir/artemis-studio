# Spec Delta: broker-config-diff

## ADDED Requirements

### Requirement: Every manageable node of a cluster is compared at once

The system SHALL expose a read that compares the broker configuration of every manageable node
of one cluster and returns, per configuration key: each node's value, the majority value, the
nodes whose value differs from the majority (the outliers), and the key's class. When no value
holds a majority, the read SHALL say so and SHALL list every distinct value with its nodes
instead of naming outliers. A request MAY narrow the comparison to a chosen set of at least two
nodes.

The read SHALL require the same permission as the topology read, SHALL be read-only, and SHALL
NOT write an audit event.

#### Scenario: One node differs from the rest
- **WHEN** four nodes are compared and one reports `max-size-bytes` 10MB while three report 100MB
- **THEN** the key reports 100MB as the majority and names that one node as the outlier with its value

#### Scenario: No majority
- **WHEN** two nodes report one value for a key and two other nodes report another
- **THEN** the key reports that there is no majority and lists both values with their nodes

#### Scenario: Read-only and unaudited
- **WHEN** a comparison is served
- **THEN** no broker state is changed and no audit event is written

### Requirement: Each key reports one comparison state

Each configuration key present on any compared node SHALL be reported in exactly one state: the
same on every node, different on some nodes, or missing on some nodes. A key missing on a node
SHALL be reported as missing there, never as an empty value. The state SHALL be a word in the
response and in the UI, not colour alone.

#### Scenario: Key missing on one node
- **WHEN** a key is present on three nodes and absent on the fourth
- **THEN** it is reported as missing on that node, not as an empty-valued difference

### Requirement: The drift review opens on drift

The view SHALL open on the keys classified as drift, grouped by section, and SHALL say in one
sentence how many keys drift on how many nodes. Each drift row SHALL show the key, the majority
value, and each outlier node with its value, the outlier's difference marked in words as well as
emphasis. Expected differences and all keys SHALL each be one switch away, and the view SHALL
offer a text filter over keys and values and a filter by node. A cluster with no drift SHALL say
so in a single statement that also counts the expected differences it set aside. The chosen
view, filter and nodes SHALL live in the URL.

#### Scenario: A clean cluster reads as clean
- **WHEN** four nodes differ only in expected keys
- **THEN** the view states that no key drifts and how many expected differences were set aside, and lists no rows

#### Scenario: Finding a drifted key
- **WHEN** one node of six has a different `redelivery-delay` for one match
- **THEN** the drift view lists exactly that key, its majority value, and that node with its value

#### Scenario: The view can be shared
- **WHEN** an operator filters to one node and copies the address
- **THEN** opening that address shows the same view, filter and node

## MODIFIED Requirements

### Requirement: Each side is read in one batched call under the rate limiter

Each compared node's configuration SHALL be read with exactly one batched request to that node,
acquired through the per-node rate limiter, so that a comparison costs at most one request per
node regardless of how many attributes or address settings it covers.

#### Scenario: One request per side
- **WHEN** a comparison of four nodes is served
- **THEN** exactly one batched request is issued to each of the four nodes

### Requirement: Address settings are keyed by their match pattern

Address settings SHALL be compared by their `match` pattern, never by position in a returned
array. Nodes that return the same set of address settings in a different order SHALL report no
drift.

#### Scenario: Reordering is not drift
- **WHEN** every node returns the same address settings in a different order
- **THEN** every address-setting key compares as the same on every node

#### Scenario: A setting present on only one side
- **WHEN** one node has an address setting whose `match` pattern the other nodes do not have
- **THEN** that setting's keys are reported as missing on the other nodes, keyed by the `match` pattern

### Requirement: An unavailable side yields no diff at all

If a node's configuration read fails, the system SHALL mark that node unavailable with the
classified failure reason and SHALL leave it out of every majority and every state, so that its
absent keys never read as missing. The remaining nodes SHALL be compared when at least two
answered; otherwise the response SHALL say that no comparison could be made and why.

#### Scenario: One node unreachable
- **WHEN** one of four nodes fails with a connection error
- **THEN** that node is reported unavailable with the reason, and the other three are compared without it

#### Scenario: Fewer than two nodes answer
- **WHEN** only one node's read succeeds
- **THEN** the response says no comparison could be made and gives each unavailable node's reason

### Requirement: A backup's reduced management surface is stated, not diffed

When a node reports that it is not active and answers with a reduced management surface, the
system SHALL state that plainly, and SHALL leave the attributes that surface does not expose out
of that node's comparison instead of reporting them as missing.

#### Scenario: Passive backup
- **WHEN** a passive backup exposes only part of the management surface its primary exposes
- **THEN** the response says the node is a passive backup with a reduced surface, and its unexposed attributes are not reported as missing on it

### Requirement: The comparison links to the declaration and states how the two differ

The node comparison SHALL link to the cluster's declared configuration and its drift report, and
the drift report SHALL link back, each stating in one sentence how they differ: the comparison
sets the nodes against each other; drift sets every live node against the declaration. Both
SHALL read a node's effective configuration through the same reader, so that they cannot report
different truths about one node.

#### Scenario: The two views name their difference
- **WHEN** an operator opens the node comparison
- **THEN** it links to the drift report and states that drift compares against the declaration rather than against the other nodes

## REMOVED Requirements

### Requirement: Two nodes of one cluster can be compared
**Reason**: Replaced by "Every manageable node of a cluster is compared at once"; a pairwise view hid drift on every node outside the chosen pair.
**Migration**: None. The `left` and `right` parameters are removed; a narrowed comparison names its nodes instead.

### Requirement: Every key is classified into one of four comparison states
**Reason**: Left-only and right-only have no meaning across more than two nodes; replaced by "Each key reports one comparison state".
**Migration**: None.
