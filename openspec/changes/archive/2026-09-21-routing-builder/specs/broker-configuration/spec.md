## ADDED Requirements

### Requirement: The declaration carries a cluster's bridges

A cluster's declaration SHALL be able to carry its bridges alongside its addresses, queues,
address settings, security settings and diverts. A declared bridge SHALL carry at least the
queue it reads from, the address it forwards to, its filter, its transformer, whether it uses
duplicate detection, whether it is highly available, its retry and reconnection behaviour, and
either the connectors it uses or the discovery group it uses — but never both, because the
broker accepts only one.

Declaring a bridge SHALL be subject to the same validation, revision, plan, apply, drift and
audit treatment as every other declared item, with no separate route or command.

#### Scenario: A bridge is part of the declaration

- **WHEN** an operator declares a bridge and saves
- **THEN** it is recorded in a new revision of the declaration alongside the other declared
  items

#### Scenario: Both connector sources are refused

- **WHEN** a declared bridge names both static connectors and a discovery group
- **THEN** the declaration is refused, naming the field, and no revision is recorded

#### Scenario: A bridge with neither connector source is refused

- **WHEN** a declared bridge names neither static connectors nor a discovery group
- **THEN** the declaration is refused, naming what is missing

### Requirement: A bridge's concurrency is accounted for when it is verified and removed

A bridge declared with a concurrency above one is deployed by the broker as that many
instances, each carrying an index appended to the declared name. The system SHALL account for
this when it verifies an applied bridge, so that a correctly deployed concurrent bridge is
reported as applied rather than as missing.

Removal SHALL use the declared name, which removes every instance.

#### Scenario: A concurrent bridge verifies as applied

- **WHEN** a bridge declared with a concurrency above one is applied to a node
- **THEN** its every deployed instance is recognised as that bridge, and the node's outcome is
  applied

#### Scenario: Removing a concurrent bridge removes every instance

- **WHEN** an operator removes a declared bridge that was deployed with a concurrency above one
- **THEN** every instance is removed

### Requirement: Bridge steps are ordered after everything they depend on

A bridge reads from a queue and forwards to an address, both of which earlier steps may
create. The plan SHALL order bridge creations after addresses, queues, address settings,
security settings and diverts, and SHALL order bridge removals before those items are removed,
so that no step ever depends on something a later step creates or an earlier step has already
taken away.

#### Scenario: A bridge is created after its queue

- **WHEN** a declaration adds both a queue and a bridge that reads from it, and the plan is
  computed
- **THEN** the queue's creation is ordered before the bridge's

#### Scenario: A bridge is removed before its queue

- **WHEN** a declaration removes both a bridge and the queue it reads from
- **THEN** the bridge's removal is ordered before the queue's

### Requirement: Bridge hazards are named before anything is written

The hazards named before an apply SHALL include: replacing a bridge, which is a removal and a
creation with a gap in which nothing is forwarded and the source queue accumulates; removing a
bridge, which stops traffic to another broker with no signal on this cluster; creating a
bridge, which begins forwarding this cluster's traffic to another broker; and declaring a
transformer, whose class cannot be verified to be present on any node before the apply runs.

#### Scenario: Replacing a bridge is named

- **WHEN** an apply would replace a declared bridge
- **THEN** the hazard is named before the apply can be confirmed, stating that nothing is
  forwarded between the removal and the creation

#### Scenario: An unverifiable transformer is named

- **WHEN** an apply would create a divert or bridge carrying a transformer class
- **THEN** the hazard is named, stating that the class cannot be verified to be loadable on
  each node until the apply runs

### Requirement: A declared transformer carries its properties and is compared like any other field

A declared divert or bridge MAY carry a transformer as a class name together with its
properties. The declaration, the plan, the difference between revisions, the exported
configuration and the imported configuration SHALL all carry the class and the properties,
unchanged and complete.

The broker reports a deployed transformer's class and its properties, so a transformer SHALL
be compared against each node on the same terms as every other declared field: a difference in
the class, or in the properties, is drift, is classified per node, and is reported by naming
the field that differs. It SHALL NOT be excluded from comparison.

#### Scenario: Properties survive the plan and the export

- **WHEN** a divert carrying a transformer with two properties is planned and exported
- **THEN** both properties appear in the plan's value and in the exported configuration

#### Scenario: A changed transformer class is drift

- **WHEN** a node runs a divert whose transformer class differs from the declared one
- **THEN** drift is reported for that node, naming the transformer class as the differing field

#### Scenario: A changed transformer property is drift

- **WHEN** a node runs a bridge whose transformer properties differ from the declared ones
- **THEN** drift is reported for that node, naming the properties as the differing field

### Requirement: Adoption does not adopt a bridge

Offering a cluster's running state as a first declaration SHALL NOT include its bridges,
because the broker reports only part of a bridge's configuration. Adopting one would declare
the unreported fields as unset, and the next apply would write the broker's defaults over
them, changing a bridge nobody edited.

An existing bridge SHALL instead be reported as observed and not declared, so it is visible
and an operator declares it deliberately.

#### Scenario: A running bridge is not adopted

- **WHEN** an operator adopts a cluster's current state as its first declaration
- **THEN** the declaration carries no bridge, and each running bridge is reported as observed
  and not declared

#### Scenario: Adoption does not silently rewrite a bridge

- **WHEN** a cluster running a bridge with a non-default window size is adopted and applied
- **THEN** that bridge's configuration is not written, and its window size is unchanged

### Requirement: A field the broker does not report back is not claimed to have been verified

The broker accepts configuration for a bridge that it never reports back, so an applied bridge
cannot be verified field for field. The system SHALL compare what the broker reports, SHALL
NOT infer agreement on what it does not, and SHALL NOT present an unverifiable field as
confirmed.

Where an apply's verification cannot cover a declared field, the system SHALL say so rather
than reporting the item as fully verified.

#### Scenario: Verification states what it covered

- **WHEN** a bridge carrying a field the broker does not report is applied and verified
- **THEN** the outcome states that the field could not be verified, and does not report it as
  matching

#### Scenario: An unreportable field is not drift

- **WHEN** a declared bridge differs from a node only in a field the broker does not report
- **THEN** no drift is claimed on the basis of that field, because no evidence exists either way
