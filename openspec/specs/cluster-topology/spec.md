## Purpose

Defines how Artemis Studio learns and maintains a cluster's node topology from
a seed, how it identifies logical nodes by their shared `NodeID`, how manual
address overrides are preserved across rediscovery, and how it derives
live-node, replication-state, and split-brain signals from periodic reads.

## Requirements

### Requirement: Topology is discovered from the seed and refreshed

The system SHALL call `listNetworkTopology()` on a reachable node to enumerate
every logical node and its advertised connectors, and SHALL persist newly
learned nodes as discovered. It SHALL re-run discovery on demand and on a schedule.

#### Scenario: Pair discovered from one seed

- **WHEN** discovery runs against a primary whose topology lists a backup
- **THEN** the backup is persisted as a discovered node under the same cluster

#### Scenario: Rediscovery on demand

- **WHEN** a rediscover request is made for a cluster
- **THEN** discovery re-runs and the node set is reconciled with the current topology

### Requirement: A logical node is keyed by NodeID

The system SHALL treat `NodeID` as the identity of a logical node. Two
endpoints reporting the same `NodeID` SHALL be represented as one logical node
with a live side and a backup side, not as two independent nodes.

#### Scenario: Synced backup shares the primary's NodeID

- **WHEN** a backup reports the same `NodeID` as its primary
- **THEN** Studio groups both endpoints under that one `NodeID`

#### Scenario: Survivor keeps the NodeID through failover

- **WHEN** a backup becomes live and continues to report the pair's `NodeID`
- **THEN** Studio still treats it as the same logical node

### Requirement: Discovered connectors that are not management URLs are surfaced, not dialed

When `listNetworkTopology()` yields a node whose only known address is a
broker-to-broker connector (not a reachable Jolokia URL), the system SHALL
record that node as known-but-not-manageable and SHALL present it as a normal
next step inviting the operator to supply a management URL, rather than as an error.

#### Scenario: Internal hostname advertised

- **WHEN** a discovered node's connector is an internal hostname Studio cannot
  reach for management
- **THEN** the node is stored with its connector and no management URL, and marked
  as needing one

#### Scenario: Operator supplies the management URL

- **WHEN** the operator adds a reachable Jolokia URL for that node
- **THEN** the node becomes manageable and is refreshed on the normal schedule

### Requirement: Manual address overrides are never overwritten by discovery

A node whose address has been set manually SHALL be marked as overridden, and
subsequent discovery runs SHALL NOT change its addresses. Only an explicit
update SHALL change an overridden node's address. This applies independently to
the node's management (Jolokia) URL and its Core URL: either may be set
manually, a manual value SHALL take precedence over the value learned from
topology, and a node-override request SHALL supply at least one of the two.

#### Scenario: Override survives rediscovery

- **WHEN** a node's management URL is set manually and rediscovery then runs
- **THEN** that node's address is unchanged and it remains marked as overridden

#### Scenario: Manual Core URL survives rediscovery

- **WHEN** a node's Core URL is set manually and rediscovery then runs
- **THEN** the node's Core URL is unchanged and it remains marked as overridden

#### Scenario: Node override requires at least one URL

- **WHEN** a node-override request supplies neither a management URL nor a Core URL
- **THEN** the request is rejected with a validation message

#### Scenario: Missing side is not a deletion

- **WHEN** a topology response omits the `backup` entry for a pair (for example
  right after failover)
- **THEN** Studio treats the backup side as not currently announced and does not
  delete the node row

### Requirement: Live node and replication state are read, never configured

The system SHALL determine which endpoint is serving from the polled `Active`
attribute and node health from the polled `Started` attribute, treating a
backup with `Started` true and `Active` false as healthy. It SHALL flag
replication as behind when a node reporting `Backup` true reports `ReplicaSync`
false. It SHALL NOT infer any of this from broker configuration.

#### Scenario: Healthy standby is not reported as down

- **WHEN** a backup reports `Started` true and `Active` false
- **THEN** its state is healthy standby, not down

#### Scenario: Replication behind

- **WHEN** a node reports `Backup` true and `ReplicaSync` false
- **THEN** the cluster health reports replication as not caught up for that pair

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

### Requirement: The topology graph groups each logical node's endpoints

The topology graph SHALL render the endpoints of one logical node as members of a single
visible group, and that grouping SHALL remain registered to its endpoints under every
pan and zoom of the canvas. The shared `NodeID` SHALL be shown on the group. A logical
node with a single endpoint SHALL still render as a group.

Within a group, vertical position SHALL encode HA role: the serving endpoint above the
group's mid-line, a standby below it. Both endpoints of a group appearing above the
mid-line SHALL be the graph's rendering of split-brain.

#### Scenario: Grouping survives interaction

- **WHEN** an operator pans or zooms the topology canvas
- **THEN** each group and its mid-line stay aligned with the endpoints they group

#### Scenario: Split-brain reads as two serving endpoints in one group

- **WHEN** a logical node's two endpoints both report serving
- **THEN** both are rendered above that group's mid-line

#### Scenario: A lone endpoint is still a group

- **WHEN** a logical node has one endpoint
- **THEN** it renders as a group containing that one endpoint

### Requirement: Endpoint state is distinguished by mark shape, not by brightness alone

Each endpoint's state mark SHALL be distinguishable by shape — serving, standby,
replication-behind, and unmanaged each having a distinct mark — so that the states are
told apart without relying on a difference in brightness or colour. Colour SHALL enter
only for a fault state. The legend SHALL be rendered as part of the view, not as an
overlay that can cover nodes, and SHALL name every mark and edge style the graph uses.

#### Scenario: Serving and standby are told apart without colour

- **WHEN** a group shows a serving endpoint and a standby endpoint
- **THEN** their marks differ in shape, and neither uses colour to say it is healthy

#### Scenario: Legend covers what is drawn

- **WHEN** the topology graph renders
- **THEN** the legend names each state mark and each edge style the graph can draw

### Requirement: An unmanaged endpoint offers the action that fixes it

An endpoint discovered without a usable management URL SHALL offer, in the graph, an
action that opens the add-a-management-URL flow for that endpoint, rather than a
disabled control or a direction to navigate elsewhere. Where the graph is rendered
without that action available — such as a static example — the invitation SHALL be
rendered as plain text and not as a control that cannot be used.

#### Scenario: Adding a URL from the graph

- **WHEN** an operator activates the call to action on an unmanaged endpoint
- **THEN** the add-a-management-URL flow opens for that endpoint

#### Scenario: No dead controls in an example

- **WHEN** the graph is rendered in a context with no add-a-management-URL action
- **THEN** the invitation is shown as text rather than as a disabled button

### Requirement: The graph states its empty and loading conditions

A cluster whose topology holds no nodes SHALL render an explanation of why the graph is
empty together with the action that can change it — rediscovery — and not a blank frame.
The add-a-management-URL flow is deliberately NOT offered here: it attaches a URL to a
discovered endpoint, and a cluster with no nodes has none. While the topology is loading,
the canvas frame SHALL be occupied by a placeholder of the graph's own size rather than a
bare spinner in an empty area.

#### Scenario: Empty cluster

- **WHEN** a cluster's topology contains no nodes
- **THEN** the graph area explains that Studio learns topology from the first broker it
  reaches and that nothing has answered on the seed address, and offers rediscovery

#### Scenario: Loading

- **WHEN** the topology has not yet loaded
- **THEN** the canvas frame shows a placeholder occupying the graph's area

### Requirement: The graph is keyboard reachable and states its interactivity

Every focusable endpoint in the graph SHALL show a visible focus indicator when reached
by keyboard. The canvas SHALL expose visible zoom-in, zoom-out, and fit controls. The
initial view SHALL fit the cluster without magnifying a small cluster beyond its natural
size, and SHALL re-fit when the set of nodes changes rather than leaving a stale
viewport after a failover.

#### Scenario: Keyboard focus is visible

- **WHEN** an operator moves focus to an endpoint with the keyboard
- **THEN** a focus indicator is shown on that endpoint

#### Scenario: A small cluster is not magnified

- **WHEN** a cluster with a single pair is rendered in a wide viewport
- **THEN** the graph is fitted without being scaled above its natural size

#### Scenario: Node set change re-fits

- **WHEN** the set of logical nodes changes while the graph is open
- **THEN** the view is re-fitted to the new node set
