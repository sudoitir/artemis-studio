## MODIFIED Requirements

### Requirement: Split-brain requires corroborated evidence

The system SHALL flag split-brain only when two endpoints sharing one `NodeID`
both report `Active` true, observed within the same refresh cycle, and the same
condition is still true on the next consecutive refresh cycle. A first
single-cycle observation SHALL be reported as suspected, not critical. Readings
from two different refresh cycles SHALL NOT by themselves raise the flag.

The refresh-cycle counter and the one-cycle corroboration state SHALL be
maintained per cluster by the scrape schedule and advanced only by it. Reading
the topology or health view SHALL NOT advance the counter or the corroboration
state.

#### Scenario: First sighting is suspected

- **WHEN** two nodes with the same `NodeID` first both read `Active` true in one cycle
- **THEN** the health status is suspected split-brain, not critical

#### Scenario: Confirmed on the next cycle

- **WHEN** the same two nodes still both read `Active` true on the next cycle
- **THEN** the health status is critical split-brain

#### Scenario: Planned failover does not false-alarm

- **WHEN** one node reads `Active` true in cycle N and the other reads `Active`
  true only in cycle N+1 (sampling skew during failover)
- **THEN** no split-brain flag is raised

#### Scenario: Reading health does not corroborate

- **WHEN** the health view is requested several times between two refresh cycles
  while a split-brain is suspected
- **THEN** the status stays suspected until the next refresh cycle confirms or clears it

#### Scenario: Cycles are per cluster

- **WHEN** two clusters are being scraped
- **THEN** each advances its own refresh-cycle counter independently and a
  split-brain in one has no effect on the other's corroboration state

### Requirement: Capabilities, topology, and health are separately readable

The system SHALL expose, per cluster, a capabilities view, a topology view
(logical nodes, their endpoints, addresses, roles, and whether each is
manageable or overridden), and a health view (live endpoint per pair,
replication state, and split-brain status).

The topology and health views SHALL be served from the most recent persisted
scrape results, not from a fresh broker probe performed on each read.

#### Scenario: Health view content

- **WHEN** the health view for a cluster is requested
- **THEN** it reports, per logical node, which endpoint is live, whether
  replication is caught up, and the split-brain status (none, suspected, or critical)

#### Scenario: Topology view content

- **WHEN** the topology view for a cluster is requested
- **THEN** each logical node lists its endpoints with role, address, manageable
  flag, and overridden flag

#### Scenario: Reads do not probe the broker

- **WHEN** the topology or health view is requested
- **THEN** the response is built from persisted scrape data and no broker request
  is issued to satisfy the read
