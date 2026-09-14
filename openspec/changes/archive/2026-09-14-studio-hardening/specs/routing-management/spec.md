## MODIFIED Requirements

### Requirement: Divert mutations apply across live nodes and report per node

A divert exists per node. The system SHALL apply a divert creation or deletion to
every live node of the cluster, determined from observed topology, and SHALL report
the outcome for each node, using the same per-node outcome vocabulary as other
cluster-wide topology mutations.

A node's creation outcome SHALL reflect what the broker actually deployed, checked after the
request. The outcome is:
- applied, when the divert is now present with the requested configuration;
- already, when an identical divert was present before;
- failed, naming the differing fields, when a divert of that name exists with a different
  configuration;
- failed, when the broker accepted the request but did not deploy the divert.

The system SHALL support previewing such a mutation, naming the target nodes and any refusal
or warning the preflight raises, without changing any broker.

The system SHALL NOT undo the nodes on which the mutation succeeded when it fails on
another; it SHALL report the divergence.

#### Scenario: A divert is created across the cluster

- **WHEN** an operator creates a divert on a cluster
- **THEN** it is created on every live node and the result reports each node

#### Scenario: A preview changes nothing

- **WHEN** an operator previews creating a divert
- **THEN** the target nodes are named and no broker is changed

#### Scenario: A duplicate name is not reported as applied

- **WHEN** an operator creates a divert whose name already exists on a node with a different configuration
- **THEN** that node's outcome is failed and names the fields that differ, rather than applied

#### Scenario: An identical existing divert is reported as already present

- **WHEN** an operator creates a divert identical to one already on a node
- **THEN** that node's outcome is already

#### Scenario: An undeployed divert is reported as failed

- **WHEN** the broker accepts a divert creation but does not deploy the divert
- **THEN** that node's outcome is failed, stating that the broker declined to deploy it and that its log holds the reason

### Requirement: Capture diverts are identified as Studio's own and are not managed here

A divert that exists to serve a message capture subscription SHALL be identified as such
wherever diverts are listed, together with the subscription it serves.

The system SHALL NOT offer to delete such a divert from the routing view. Removing it there
would leave a capture subscription whose tap is reinstated on the next reconciliation, so
the deletion would appear to succeed and then silently undo itself. The routing view SHALL
instead lead to the subscription that owns it.

The system SHALL refuse, on every interface (the REST API and the MCP tools included), to
create or delete a divert whose name is in the namespace reserved for capture. The refusal
SHALL name the capture subscription to manage instead.

#### Scenario: A capture divert is attributed to its subscription

- **WHEN** an operator views a cluster's diverts while a capture subscription is active
- **THEN** the capture divert is listed, identified as serving message capture, and names the subscription it belongs to

#### Scenario: Deleting a capture divert is not offered in the routing view

- **WHEN** an operator opens a capture divert in the routing view
- **THEN** no delete action is offered there, and the view leads to the capture subscription that owns it

#### Scenario: The API refuses to delete a capture divert

- **WHEN** a client calls the divert delete API or tool with a capture divert's name
- **THEN** the request is refused with the reason, and no broker is changed

#### Scenario: The API refuses to create a divert in the capture namespace

- **WHEN** a client creates a divert whose name is in the capture namespace
- **THEN** the request is refused with the reason, and no broker is changed

## ADDED Requirements

### Requirement: A divert that would break routing is refused before it is created

Before creating a divert, in both the preview and the real request, the system SHALL check
each target node. It SHALL refuse the creation, naming the condition, when:
- the source and forwarding addresses are the same;
- the forwarding address does not exist on a node and would not be created automatically
  there, because producers to the source address would then fail;
- the new divert would complete a cycle of diverts, in which case the cycle is named.

When the new divert is exclusive and its source address carries a capture tap, the system
SHALL warn that capture of that address would observe nothing. It SHALL require the operator
to acknowledge this explicitly before creating it.

#### Scenario: A missing forwarding address is refused

- **WHEN** an operator creates a divert to a forwarding address that does not exist on a node and is not auto-created there
- **THEN** the creation is refused for that node, naming the missing address and the configuration that would create it, and no divert is created there

#### Scenario: A divert cycle is refused

- **WHEN** a divert from address A to address B exists and an operator creates a divert from B to A
- **THEN** the creation is refused, naming the cycle

#### Scenario: Source equal to forwarding is refused

- **WHEN** an operator creates a divert whose forwarding address equals its source address
- **THEN** the request is refused with the reason

#### Scenario: Shadowing a capture requires acknowledgement

- **WHEN** an operator previews an exclusive divert on an address that has a capture tap
- **THEN** the preview warns that capture of that address would observe nothing, and the creation is refused unless the operator acknowledges it

### Requirement: Divert requests are validated and generated configuration is well-formed

The system SHALL validate a divert request before any broker is contacted. It SHALL reject,
field by field:
- a missing name, source or forwarding address;
- a name longer than the permitted length, or containing characters that are not safe in
  a broker management name;
- a routing type that is not one of the broker's routing types.

The broker configuration the system shows for a divert SHALL be well-formed XML, whatever
characters the name, addresses or filter contain.

#### Scenario: An invalid request is rejected per field

- **WHEN** a client submits a divert with an unknown routing type and a name containing a comma
- **THEN** the request is rejected with an error for each of those fields, and no broker is contacted

#### Scenario: A filter with XML-special characters yields valid configuration

- **WHEN** a divert's filter contains `<`, `&` or a quote
- **THEN** the broker configuration shown for it parses as XML and carries the filter unchanged
