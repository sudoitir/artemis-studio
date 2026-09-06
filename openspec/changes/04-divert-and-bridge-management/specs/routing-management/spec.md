## ADDED Requirements

### Requirement: Diverts and bridges are visible across the cluster

The system SHALL present the diverts and the bridges of a cluster, merged across its
nodes, at the same quality as its other resource views — paged, filterable, and
attributed to the nodes each was found on.

A bridge SHALL carry its running state, so that an operator can answer whether a
configured bridge is actually running.

Viewing diverts and bridges SHALL require only the permission needed to view a
cluster.

#### Scenario: The routing between endpoints is visible

- **WHEN** an operator views a cluster's diverts
- **THEN** each divert is listed with its source address, forwarding address,
  filter, exclusivity, and the nodes it exists on

#### Scenario: A bridge's running state is answerable

- **WHEN** an operator views a cluster's bridges
- **THEN** each bridge is listed with whether it is currently running

### Requirement: A divert is classified by whether it will survive a restart

The system SHALL compare the diverts running on each node against that node's
effective configuration, and SHALL classify each as configured and running, running
but not configured, or configured but not running.

A divert that is running but not configured SHALL be marked as such wherever it is
listed — not only at the moment it is created — because its defining property is
that it will disappear at the next restart of that broker, long after whoever
created it has moved on.

The comparison SHALL use the same effective-configuration source the system uses to
compare configuration between nodes, so that the two features cannot disagree about
what a node is configured to do.

#### Scenario: A runtime-only divert is flagged

- **WHEN** a divert is present in a running broker and absent from its effective
  configuration
- **THEN** it is listed as running but not configured, with the consequence stated

#### Scenario: A divert created outside the system is classified correctly

- **WHEN** a runtime divert was created by some other tool
- **THEN** it is classified by the same comparison and flagged the same way

#### Scenario: Configuration that has not taken effect is surfaced

- **WHEN** a divert is present in a node's effective configuration and absent from
  the running broker
- **THEN** it is listed as configured but not running

### Requirement: Creating a divert states that it will not survive a restart

The system SHALL allow an authorized operator to create and to delete a divert, and
SHALL state, before the action is armed, that the divert will be lost when the
broker restarts, together with the configuration that would make it permanent.

The configuration shown SHALL be generated from the values the operator supplied, so
it can be applied directly rather than re-entered.

The system SHALL NOT write to broker configuration files.

#### Scenario: The persistence consequence is stated before the action

- **WHEN** an operator is about to create a divert
- **THEN** the form states that it will be lost when the broker restarts and shows
  the configuration that would make it permanent, built from the entered values

#### Scenario: The system does not edit broker configuration

- **WHEN** a divert is created
- **THEN** the running broker is changed and no broker configuration file is written

### Requirement: Divert mutations apply across live nodes and report per node

A divert exists per node. The system SHALL apply a divert creation or deletion to
every live node of the cluster, determined from observed topology, and SHALL report
the outcome for each node, using the same per-node outcome vocabulary as other
cluster-wide topology mutations.

The system SHALL support previewing such a mutation, naming the target nodes without
changing any broker.

The system SHALL NOT undo the nodes on which the mutation succeeded when it fails on
another; it SHALL report the divergence.

#### Scenario: A divert is created across the cluster

- **WHEN** an operator creates a divert on a cluster
- **THEN** it is created on every live node and the result reports each node

#### Scenario: A preview changes nothing

- **WHEN** an operator previews creating a divert
- **THEN** the target nodes are named and no broker is changed

### Requirement: Bridges are never mutated through the system

The system SHALL NOT offer creating, changing, or removing a bridge or a cluster
connection. Such a change alters how a cluster is wired to other brokers, is
invisible to whatever manages the cluster's configuration, and does not survive a
restart.

#### Scenario: No bridge mutation is offered

- **WHEN** an operator views a bridge
- **THEN** no action that would create, change, or remove it is available

### Requirement: Changing a divert is an explicit delete and create

The system SHALL NOT offer an in-place update of an existing divert. An operator who
needs to change one SHALL delete it and create the replacement, each step confirmed
and audited separately, so that no partially applied change is presented as atomic.

#### Scenario: No in-place edit is offered

- **WHEN** an operator opens an existing divert
- **THEN** its properties are shown and no edit action is offered, with delete and
  create presented as the way to change it

### Requirement: Impermanence is a persistent state in the view, not a one-time warning

A divert that will not survive a restart SHALL be marked in every view that lists
it, with wording that names the event that will remove it. Wording that describes it
only as temporary SHALL NOT be used, because it implies the system will clean it up.

The marking SHALL be carried in text, not by colour alone, and SHALL be readable by
an operator who did not create the divert and has no memory of a warning shown at
creation time.

Selecting the marking SHALL lead to what would make the divert permanent.

#### Scenario: The marking survives the session that created it

- **WHEN** an operator who did not create a runtime-only divert views the list
- **THEN** it is marked, in text, as one that will be lost when the broker restarts

#### Scenario: The marking leads to the remedy

- **WHEN** an operator selects the marking on a runtime-only divert
- **THEN** the configuration that would make it permanent is shown

### Requirement: The creation form states the impermanence before the action, with the remedy attached

The form that creates a divert SHALL state, before the creating control is armed,
that the divert will be lost when the broker restarts, and SHALL show the
configuration that would make it permanent, generated from the values entered so it
can be applied without being retyped.

That configuration SHALL be copyable in one action.

The statement SHALL sit with the action, not in a dismissible notice that can be
skipped past.

#### Scenario: The consequence cannot be skipped past

- **WHEN** an operator fills in the divert creation form
- **THEN** the impermanence is stated with the action itself, not in a dismissible
  notice

#### Scenario: The configuration is copyable, not retypable

- **WHEN** an operator wants to make a divert permanent
- **THEN** the configuration built from their entered values can be copied in one
  action

### Requirement: A routing view answers where a message goes without being read end to end

The divert view SHALL make the source and the forwarding address of each divert
legible as a direction, so an operator scanning for "where does traffic on this
address go" is not reconstructing it from two columns.

Where a divert is exclusive — diverting rather than copying — that SHALL be
distinguishable at a glance, because it is the difference between traffic being
duplicated and traffic being taken away.

The view SHALL be filterable by address so that the question "what touches this
address" is answerable directly.

#### Scenario: Direction is legible

- **WHEN** an operator scans the divert view
- **THEN** the direction from source to forwarding address is apparent without
  cross-referencing columns

#### Scenario: Exclusive diverts are distinguishable

- **WHEN** a view contains both exclusive and non-exclusive diverts
- **THEN** the difference is apparent, and conveyed in text rather than by colour
  alone

#### Scenario: What touches an address is answerable

- **WHEN** an operator filters the divert view by an address
- **THEN** the diverts that read from or write to it are shown
