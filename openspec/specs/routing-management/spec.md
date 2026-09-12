# routing-management Specification

## Purpose
Covers how a cluster's message routing — its diverts and its bridges — is made visible
across nodes, and the disclosure that a divert created through the system persists on the
broker while remaining absent from whatever manages that broker's configuration.

## Requirements

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

### Requirement: The system does not claim where a divert came from

Artemis exposes no marker recording whether a divert was declared in broker configuration or
created over the management API, and no operation that returns configured but undeployed
diverts. The system SHALL NOT present a divert's origin as though it were known.

The system SHALL identify the diverts it owns itself, from its own records, and SHALL treat
every other divert as one whose origin it cannot establish.

The system SHALL NOT describe a divert as temporary, runtime-only, or as one that will be lost
when the broker restarts. Configuration created over the management API survives a restart,
so such wording would be false and would invite an operator to leave something in place
expecting it to clear itself.

#### Scenario: A divert Studio did not create carries no origin claim

- **WHEN** an operator views a divert the system does not own
- **THEN** it is listed with what it does, and no statement is made about whether it came
  from broker configuration

#### Scenario: No divert is described as temporary

- **WHEN** any divert is listed
- **THEN** nothing in the view says it will be lost when the broker restarts

### Requirement: A divert created through the system is disclosed as configuration drift

A divert created over the management API persists on the broker and is absent from the
configuration whatever manages that broker will next deploy. The system SHALL state this
consequence — that the running broker and its configuration will disagree, silently, until
one of them is changed — and SHALL show the configuration that would make them agree.

The configuration shown SHALL be generated from the values the operator supplied, so it can be
applied directly rather than re-entered, and SHALL be copyable in one action.

A divert MAY also be declared in the cluster's declared configuration, in which case the
declaration's drift report is where its presence on every node is tracked.

#### Scenario: The drift consequence is stated before the action

- **WHEN** an operator is about to create a divert
- **THEN** the form states that the divert will persist on the broker and will not appear in
  its configuration, and shows the configuration that would make the two agree

#### Scenario: The configuration is copyable, not retypable

- **WHEN** an operator wants the created divert reflected in configuration
- **THEN** the configuration built from their entered values can be copied in one action

### Requirement: Creating and deleting a divert is an authorized, audited act

The system SHALL allow an authorized operator to create and to delete a divert, and SHALL
NOT write to broker configuration files.

Because nothing removes a divert on the system's behalf, deletion SHALL be presented as the
only thing that removes one.

#### Scenario: Deletion is presented as the only removal

- **WHEN** an operator views a divert the system created
- **THEN** deleting it is presented as what removes it, with no suggestion that a restart
  or any other event will

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
connection. Such a change alters how a cluster is wired to other brokers and is invisible
to whatever manages the cluster's configuration, which would leave the wiring between
brokers diverging silently from what anyone deploying that cluster believes it to be.

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

### Requirement: Drift is a persistent state in the view, not a one-time warning

A divert the system owns SHALL be marked wherever it is listed, with the configuration that
would make the broker and its configuration agree reachable from that marking.

The marking SHALL be carried in text, not by colour alone, and SHALL be readable by an
operator who did not create the divert and has no memory of anything shown at creation time.

Wording that describes such a divert as temporary SHALL NOT be used, because it implies
something will remove it.

#### Scenario: The marking survives the session that created it

- **WHEN** an operator who did not create a system-owned divert views the list
- **THEN** it is marked, in text, as one the system created and one that broker
  configuration does not carry

#### Scenario: The marking leads to the remedy

- **WHEN** an operator selects the marking
- **THEN** the configuration that would make the broker and its configuration agree is shown

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

### Requirement: Capture diverts are identified as Studio's own and are not managed here

A divert that exists to serve a message capture subscription SHALL be identified as such
wherever diverts are listed, together with the subscription it serves.

The system SHALL NOT offer to delete such a divert from the routing view. Removing it there
would leave a capture subscription whose tap is reinstated on the next reconciliation, so
the deletion would appear to succeed and then silently undo itself. The routing view SHALL
instead lead to the subscription that owns it.

#### Scenario: A capture divert is attributed to its subscription

- **WHEN** an operator views a cluster's diverts while a capture subscription is active
- **THEN** the capture divert is listed, identified as serving message capture, and names the subscription it belongs to

#### Scenario: Deleting a capture divert is not offered in the routing view

- **WHEN** an operator opens a capture divert in the routing view
- **THEN** no delete action is offered there, and the view leads to the capture subscription that owns it
