## Purpose

Defines how Artemis Studio compares the effective broker configuration of two nodes in
one cluster — most usefully a primary and its backup — so that configuration drift that
would only surface at failover is visible beforehand, and so that a difference which is
correct by design is never reported as drift.

## Requirements

### Requirement: Two nodes of one cluster can be compared

The system SHALL expose a read that compares the broker configuration of two named
nodes in one cluster and returns a per-key comparison. When only one node is named,
the system SHALL default the comparison to the two endpoints of that node's logical
node — its HA pair. Any two nodes of the cluster MAY be compared.

The read SHALL require the same permission as the topology read, SHALL be read-only,
and SHALL NOT write an audit event, matching the rule that only mutating calls audit.

#### Scenario: Pair is the default

- **WHEN** a config comparison is requested naming only one node
- **THEN** the response compares that node against the other endpoint of its logical
  node and states which two nodes were compared

#### Scenario: Arbitrary pair is allowed

- **WHEN** a config comparison names two nodes of the same cluster that are not an HA pair
- **THEN** those two nodes are compared

#### Scenario: Read-only and unaudited

- **WHEN** a config comparison is served
- **THEN** no broker state is changed and no audit event is written

### Requirement: Each side is read in one batched call under the rate limiter

Each side's configuration SHALL be read with exactly one batched request to that node,
acquired through the per-node rate limiter, so that a comparison costs at most one
request per node regardless of how many attributes or address settings it covers.

#### Scenario: One request per side

- **WHEN** a comparison of two nodes is served
- **THEN** exactly one batched request is issued to each node

### Requirement: Every key is classified into one of four comparison states

Each configuration key present on either side SHALL be reported in exactly one of four
states: identical on both sides, present on both sides with different values, present
only on the left node, or present only on the right node. The state SHALL be reported
as a word in the response and rendered as a word in the UI, not by colour alone.

#### Scenario: Differing value

- **WHEN** a key is present on both nodes with different values
- **THEN** it is reported as different, with both values

#### Scenario: Key missing on one side

- **WHEN** a key is present on the left node and absent on the right
- **THEN** it is reported as present only on the left, and not as an empty-valued difference

### Requirement: Address settings are keyed by their match pattern

Address settings SHALL be compared by their `match` pattern, never by position in a
returned array. Two nodes that return the same set of address settings in a different
order SHALL report no drift.

#### Scenario: Reordering is not drift

- **WHEN** both nodes return the same address settings in a different order
- **THEN** every address-setting key compares as identical

#### Scenario: A setting present on only one side

- **WHEN** the left node has an address setting whose `match` pattern the right node
  does not have
- **THEN** that setting's keys are reported as present only on the left, keyed by the
  `match` pattern

### Requirement: Configuration is classified, never silently filtered

Keys SHALL be classified rather than filtered. A known set of configuration attributes
SHALL populate a Configuration section; every remaining key — including runtime counters
and attributes a future broker version introduces — SHALL appear in a separate
Unclassified section, marked as unclassified and collapsed by default. No key returned
by either node SHALL be dropped from the response without being shown somewhere.

#### Scenario: Unknown attribute is surfaced, not dropped

- **WHEN** a node returns an attribute that is not in the known configuration set
- **THEN** that attribute appears in the Unclassified section rather than being omitted

#### Scenario: Runtime counters do not read as drift

- **WHEN** two nodes report different values for a runtime counter such as a message or
  connection count
- **THEN** that difference appears under Unclassified, outside the Configuration section

### Requirement: Differences that are correct by design are reported as expected, not drift

Differences that are inherent to two distinct nodes — the broker name, node-local file
system paths, the NodeID, and acceptor host names — SHALL be reported in an expected
class that is visually and semantically distinct from drift. A pair with no real drift
SHALL present as a clean comparison rather than as a list of false positives.

#### Scenario: Broker name difference is expected

- **WHEN** the two nodes report different broker names
- **THEN** that difference is classified as expected, not as drift

#### Scenario: A clean pair reads as clean

- **WHEN** two nodes differ only in expected keys
- **THEN** the comparison reports no drift

### Requirement: An unavailable side yields no diff at all

If either node's configuration read fails, the system SHALL return the comparison with
that side marked unavailable and the classified failure reason, and SHALL NOT render a
partial comparison in which the unreachable side's absent keys read as removals.

#### Scenario: One node unreachable

- **WHEN** the right node's configuration read fails with a connection error
- **THEN** the response marks the right side unavailable with the classified reason and
  reports no per-key drift

### Requirement: A backup's reduced management surface is stated, not diffed

When a node reports that it is not active and answers with a reduced management surface,
the system SHALL state that plainly instead of reporting the attributes that surface
does not expose as missing configuration.

#### Scenario: Passive backup

- **WHEN** the right node reports it is not active and exposes only part of the
  management surface the left node exposes
- **THEN** the response says the node is a passive backup with a reduced surface, rather
  than reporting its unexposed attributes as present only on the left

### Requirement: A capped comparison says what it compared

When a node has more address settings than the comparison cap, the system SHALL always
compare the default `#` match, SHALL compare up to the cap, and SHALL report how many of
how many address settings were compared. Truncation SHALL NOT be silent.

#### Scenario: Cap is disclosed

- **WHEN** the nodes have more address settings than the cap
- **THEN** the response states how many of how many were compared, and includes `#`
