## MODIFIED Requirements

### Requirement: One batched request per node

For any scrape or probe that needs more than one attribute or operation from a
single node, the system SHALL send exactly one Jolokia bulk POST containing a
JSON array of request objects, mixing `read` and `exec` entries, rather than
one HTTP call per attribute, operation, or queue.

#### Scenario: HA attributes and topology in one call

- **WHEN** a refresh needs `Active`, `Started`, `Backup`, `ReplicaSync`,
  `NodeID`, `Clustered`, `Version` and `listNetworkTopology()` from a node
- **THEN** Studio sends a single POST whose body is a JSON array of those requests

#### Scenario: A configuration read is one call per node

- **WHEN** a node's configuration is read for comparison against another node —
  broker attributes, address settings, security settings and acceptors
- **THEN** Studio sends a single POST to that node containing all of those requests

#### Scenario: Per-entry errors are isolated

- **WHEN** a batched response array contains one entry with `status` 404 and an
  `error` / `error_type` field, and other entries with `status` 200
- **THEN** Studio consumes the successful entries and records the failed entry
  without discarding the batch

#### Scenario: Whole-call HTTP 200 with failed entries

- **WHEN** the batched POST returns HTTP 200 but one array entry has a non-200
  `status`
- **THEN** Studio inspects each entry's own `status` and does not treat the
  call as wholly successful
