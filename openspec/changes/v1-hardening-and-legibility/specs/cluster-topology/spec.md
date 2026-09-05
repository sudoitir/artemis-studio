## ADDED Requirements

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
empty together with the add-a-management-URL action, not a blank frame. While the
topology is loading, the canvas frame SHALL be occupied by a placeholder of the graph's
own size rather than a bare spinner in an empty area.

#### Scenario: Empty cluster

- **WHEN** a cluster's topology contains no nodes
- **THEN** the graph area explains that Studio learns topology from the first broker it
  reaches, and offers the add-a-management-URL action

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
