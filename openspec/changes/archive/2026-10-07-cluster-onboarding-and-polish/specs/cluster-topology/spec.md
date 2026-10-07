# Spec Delta: cluster-topology

## MODIFIED Requirements

### Requirement: Discovered connectors that are not management URLs are surfaced, not dialed

`listNetworkTopology()` advertises broker-to-broker connectors, never management URLs. For each
discovered node, the system SHALL derive a management URL from the cluster's management URL
pattern: the pattern's scheme, port and path, with the host of the node's connector. It SHALL
probe that URL with the management account, and SHALL attach it only when the broker answering
there reports the node's `NodeID`. A node whose derived URL does not answer, rejects the
management account, or reports another `NodeID` SHALL be recorded as known but not manageable,
with that reason, and SHALL be presented as a next step inviting the operator to supply a
management URL, not as an error. The connector itself SHALL never be dialed as a management URL.

#### Scenario: Derived URL proved by NodeID
- **WHEN** a discovered node's connector host serves management on the pattern's port and path and reports the expected `NodeID`
- **THEN** the node is stored with that management URL, marked as derived, and is manageable

#### Scenario: Derived URL answers for another broker
- **WHEN** the derived URL answers with a different `NodeID`
- **THEN** the URL is not attached, and the node is marked as needing a management URL because a different broker answered

#### Scenario: Internal hostname advertised
- **WHEN** a discovered node's connector is an internal host name Studio cannot reach
- **THEN** the node is stored with its connector and no management URL, and marked as needing one because the derived URL did not answer

#### Scenario: Operator supplies the management URL
- **WHEN** the operator adds a reachable Jolokia URL for that node
- **THEN** the node becomes manageable, its URL is marked manual, and it is refreshed on the normal schedule

### Requirement: Manual address overrides are never overwritten by discovery

Each node SHALL record where its management URL came from: a seed, derived from the pattern, or
set manually. Discovery MAY replace a derived URL, and SHALL NOT change a seed or manual URL.
Only an explicit update SHALL change a manual URL. This applies independently to the node's
management URL and its Core URL: either may be set manually, a manual value SHALL take
precedence over the value learned from topology, and a node-override request SHALL supply at
least one of the two.

#### Scenario: Override survives rediscovery
- **WHEN** a node's management URL is set manually and rediscovery then runs
- **THEN** that node's URL is unchanged and still marked manual

#### Scenario: A derived URL follows the pattern
- **WHEN** the management URL pattern changes and rediscovery runs
- **THEN** each derived URL is re-derived and re-proved, while seed and manual URLs are unchanged

#### Scenario: Manual Core URL survives rediscovery
- **WHEN** a node's Core URL is set manually and rediscovery then runs
- **THEN** the node's Core URL is unchanged and it remains marked as overridden

#### Scenario: Node override requires at least one URL
- **WHEN** a node-override request supplies neither a management URL nor a Core URL
- **THEN** the request is rejected with a validation message

#### Scenario: Missing side is not a deletion
- **WHEN** a topology response omits the `backup` entry for a pair (for example right after failover)
- **THEN** Studio treats the backup side as not currently announced and does not delete the node row

### Requirement: Topology is discovered from the seed and rediscovered on a schedule

The system SHALL call `listNetworkTopology()` on a reachable node to enumerate every logical
node and its advertised connectors, SHALL persist newly learned nodes, and SHALL derive and
prove their management URLs. It SHALL re-run discovery on its own schedule, at a cadence the
administrator can change at runtime, for every cluster with a reachable manageable node. Each
derived-URL probe SHALL go through the per-node rate limiter. A node whose derived URL did not answer
SHALL be asked again no sooner than ten minutes later, and one whose URL was refused (credentials
rejected, another broker, or the wrong endpoint) only after the pattern, the TLS bundle or the
management account changes.

#### Scenario: Pair discovered from one seed
- **WHEN** discovery runs against a primary whose topology lists a backup
- **THEN** the backup is persisted under the same cluster, with a proved management URL when its derived URL answers with its `NodeID`

#### Scenario: A refused URL is not asked again every interval
- **WHEN** a derived URL rejects the management account and the next discovery interval passes with nothing changed
- **THEN** that URL is not probed again until the pattern or the account changes

#### Scenario: Rediscovery on a schedule
- **WHEN** a broker joins a registered cluster and serves management on the cluster's pattern
- **THEN** within one discovery interval it is persisted as manageable and shown on open topology views without a reload
