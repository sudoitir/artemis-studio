## ADDED Requirements

### Requirement: A cluster's routing is presented as a graph that can be edited

The system SHALL present a cluster's routing as a graph — its addresses, its queues, the
diverts between addresses, the bridges out of queues, and the targets those bridges reach —
so that "where does traffic on this address go" is answered by following a line rather than
by cross-referencing rows on separate screens.

The graph SHALL be an editing surface for the cluster's declared configuration. Composing a
route in it SHALL change only the declaration, and SHALL change no broker until the
declaration is applied through the ordinary apply path.

Every element SHALL state which of three things it is, in words: declared and observed on the
brokers, declared but not yet applied, or observed on the brokers and not declared. These are
facts the system holds; none of them is a claim about where an element originally came from.

The graph SHALL NOT be the only way to reach anything it presents. Every element it shows and
every action it offers SHALL also be reachable from the non-graph view of the same
declaration.

#### Scenario: A route is followed rather than reconstructed

- **WHEN** an operator opens the routing graph for a cluster with a divert from one address to
  another and a bridge out of a queue on the second address
- **THEN** the path from the first address through the divert, the second address, its queue
  and the bridge to its target is drawn as connected elements

#### Scenario: Composing a route changes no broker

- **WHEN** an operator composes a divert in the graph and saves it
- **THEN** a new declaration revision is recorded and no broker has been contacted to create it

#### Scenario: An unapplied edit is distinguishable from a running one

- **WHEN** the declaration contains a divert that has not been applied to any node
- **THEN** that element states in words that it is declared and not yet applied, and removing
  colour from the view removes no information

#### Scenario: An undeclared element is shown without an origin claim

- **WHEN** a divert is observed on the brokers that the declaration does not contain
- **THEN** it is drawn and stated to be observed and not declared, and nothing states whether
  it came from broker configuration

#### Scenario: The graph is never the only route to an action

- **WHEN** an operator cannot use the graph
- **THEN** every element in it and every edit it offers remain available from the non-graph
  view of the same declaration

### Requirement: The routing graph is bounded and states its bound

A cluster's routing can be larger than a graph can usefully draw. The system SHALL bound what
it renders, and SHALL state the bound and what has been left out rather than silently drawing
a subset.

Where the graph is bounded, the system SHALL let the operator choose the address the drawn
region is anchored on, so the part they came to see is the part they get.

#### Scenario: A large cluster states what is not drawn

- **WHEN** a cluster's routing exceeds what the graph renders at once
- **THEN** the view states that it is showing a bounded region, how much is not shown, and how
  to choose what is

### Requirement: A transformer is declared with its properties, and its availability is not claimed

A divert or a bridge MAY carry a transformer: a class name together with the properties that
configure it. The system SHALL allow both to be declared, and SHALL carry the properties
through export and import unchanged.

The system SHALL NOT claim that a transformer class is available on a broker. No management
operation reports what a broker has loaded, so the system SHALL state that it cannot verify
this before applying, and SHALL NOT disable or hide the field on that basis.

When a node declines to deploy a divert or a bridge that carries a transformer, that node's
outcome SHALL name the transformer class, so the operator is told what to put on the
classpath rather than that something unspecified failed.

#### Scenario: A transformer's properties survive a round trip

- **WHEN** a divert carrying a transformer class and two properties is exported and imported
  again
- **THEN** the class and both properties are present and unchanged

#### Scenario: Availability is stated as unverifiable, not as unavailable

- **WHEN** an operator enters a transformer class
- **THEN** the field remains usable and the view states that the system cannot verify the
  class is on each broker's classpath before the configuration is applied

#### Scenario: A failed deployment names the transformer

- **WHEN** a node accepts the request but does not deploy a divert whose transformer class it
  cannot load
- **THEN** that node's outcome is failed and names the transformer class

### Requirement: A bridge is declared, applied and removed like other configuration

The system SHALL allow an authorized operator to declare a bridge as part of a cluster's
declared configuration, and SHALL apply, change and remove it through the same plan, hazard,
canary-first apply and drift machinery as every other declared item. There SHALL be no
separate path that writes a bridge to a broker.

Because the broker offers no in-place update for a bridge, a changed bridge SHALL be planned
and presented as a removal followed by a creation, and SHALL be named as a hazard: nothing is
forwarded between the two steps and the bridge's source queue accumulates.

Removing a bridge SHALL be named as a hazard in its own right, because traffic to another
broker stops and nothing on this cluster reports that it has.

A bridge's running state SHALL continue to be reported as observed state, separate from
whether its configuration matches what is declared. A declared bridge whose configuration
matches but which is not connected SHALL be reported as a fault, not as drift.

#### Scenario: A declared bridge is applied like any other item

- **WHEN** an operator declares a bridge and applies the declaration
- **THEN** it is created on each node through the same canary-first apply, and each node's
  outcome is reported

#### Scenario: A changed bridge is planned as a removal and a creation

- **WHEN** an operator changes a declared bridge's forwarding address and reviews the plan
- **THEN** the plan shows a removal followed by a creation, and states that nothing is
  forwarded between them

#### Scenario: Removing a bridge is named as a hazard

- **WHEN** an operator plans the removal of a declared bridge
- **THEN** the plan states that traffic to that bridge's target stops and that no signal on
  this cluster will report it

#### Scenario: A disconnected bridge is a fault, not drift

- **WHEN** a node runs a bridge whose configuration matches the declaration but which is not
  connected to its target
- **THEN** it is reported as not connected, and is not reported as configuration drift

### Requirement: A bridge's credentials are held as a reference, never as declared configuration

A bridge may authenticate to the broker it forwards to. The system SHALL hold such credentials
in its own secret storage and SHALL carry only a reference to them in the declaration.

The credential SHALL NOT appear in the declaration document, in any stored revision of it, in
a difference between revisions, in an audit record's parameters, in any tool or API response,
or in exported configuration. Exported configuration SHALL carry a placeholder naming the
credential that must be supplied.

#### Scenario: The declaration carries a reference, not a secret

- **WHEN** an operator declares a bridge with a user and password
- **THEN** the stored declaration carries a reference to the credential and no password

#### Scenario: Export does not disclose the credential

- **WHEN** a declaration containing an authenticated bridge is exported
- **THEN** the exported configuration names the credential that must be supplied and contains
  no password

#### Scenario: A revision difference does not disclose the credential

- **WHEN** an operator compares two revisions that differ in a bridge's credential
- **THEN** the difference states that the credential changed and shows neither value

## REMOVED Requirements

### Requirement: Bridges are never mutated through the system

**Reason**: The prohibition rested on a bridge change being invisible to whatever manages the
cluster's configuration, so that the wiring between brokers would diverge silently from what
anyone deploying that cluster believed it to be. A bridge declared in the cluster's declared
configuration is not in that position: the declaration is what the cluster is configured to
be, the difference between it and each node is reported as drift, and exporting it produces
the configuration that makes a deployment carry the bridge. The concern that motivated the
prohibition is answered by the mechanism rather than by refusing the capability.

**Migration**: Bridges are now declared alongside addresses, queues, address settings,
security settings and diverts, and are applied by the same canary-first apply. Nothing that
previously worked stops working: bridges remain visible across the cluster with their running
state, and a deployment that does not use Studio's declaration is unaffected. An operator who
does not want bridges changed from Studio withholds the permission that the declaration's
apply already requires.
