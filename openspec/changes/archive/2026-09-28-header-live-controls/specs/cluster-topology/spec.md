## REMOVED Requirements

### Requirement: Topology is discovered from the seed and refreshed

**Reason**: Rediscovery on demand is replaced by rediscovery on a schedule (ADR-0119).
**Migration**: None needed. New brokers appear within one discovery interval; shorten `scrape.discovery-interval` in Settings to see them sooner.

## ADDED Requirements

### Requirement: Topology is discovered from the seed and rediscovered on a schedule

The system SHALL call `listNetworkTopology()` on a reachable node to enumerate
every logical node and its advertised connectors, and SHALL persist newly
learned nodes as discovered. It SHALL re-run discovery on its own schedule, at a
cadence the administrator can change at runtime, for every cluster with a
reachable manageable node.

#### Scenario: Pair discovered from one seed

- **WHEN** discovery runs against a primary whose topology lists a backup
- **THEN** the backup is persisted as a discovered node under the same cluster

#### Scenario: Rediscovery on a schedule

- **WHEN** a broker joins a registered cluster
- **THEN** within one discovery interval it is persisted as a discovered node and shown on
  open topology views without a reload

## MODIFIED Requirements

### Requirement: The graph states its empty and loading conditions

A cluster whose topology holds no nodes SHALL render an explanation of why the graph is
empty, and that Studio keeps looking on its own schedule, and not a blank frame.
The add-a-management-URL flow is deliberately NOT offered here: it attaches a URL to a
discovered endpoint, and a cluster with no nodes has none. While the topology is loading,
the canvas frame SHALL be occupied by a placeholder of the graph's own size rather than a
bare spinner in an empty area.

#### Scenario: Empty cluster

- **WHEN** a cluster's topology contains no nodes
- **THEN** the graph area explains that Studio learns topology from the first broker it
  reaches, that nothing has answered on the seed address, and that it keeps looking

#### Scenario: Loading

- **WHEN** the topology has not yet loaded
- **THEN** the canvas frame shows a placeholder occupying the graph's area
