## ADDED Requirements

### Requirement: An operator can declare what a cluster is expected to contain

The system SHALL allow an authorized operator to declare the queues and addresses a
cluster is expected to have, recording for each its name, its address, its routing
type, its durability, and the configuration values the operator wishes to pin.

The declaration SHALL be storable per cluster, SHALL persist independently of any
broker-derived cache, and SHALL survive the deletion of such a cache.

The system SHALL support creating a declaration from a cluster's current state, so
that an operator adopting the feature does not have to enter it by hand, and SHALL
report what has changed when a declaration is regenerated later.

#### Scenario: A declaration is adopted from what exists

- **WHEN** an operator creates a declaration for a cluster from its current state
- **THEN** the cluster's existing queues and addresses are recorded as declared, and
  the operator can edit the result

#### Scenario: Clearing broker-derived caches does not affect a declaration

- **WHEN** the broker-derived resource cache is cleared
- **THEN** the cluster's declaration is unchanged

#### Scenario: Regenerating shows what has changed

- **WHEN** an operator regenerates a declaration for a cluster some time later
- **THEN** the differences from the stored declaration are reported before anything
  is replaced

### Requirement: The declaration records intent and is not authoritative for brokers

The declaration SHALL be understood and presented as the system's record of what an
operator expects, not as configuration a broker reads. Broker configuration remains
authoritative for what a broker does.

Drift SHALL therefore be reported as a disagreement between the declaration and the
observed cluster, and SHALL NOT be described as the broker being wrong.

Editing a declaration SHALL NOT change any broker.

#### Scenario: Editing the declaration changes nothing on a broker

- **WHEN** an operator edits a cluster's declaration
- **THEN** no broker is contacted and no broker state changes

### Requirement: Drift is classified and attributed to nodes

The system SHALL compare the declaration against what each node of the cluster
actually reports, and SHALL classify each finding as declared but missing, present
but not declared, or present but configured differently from the declaration.

A missing finding SHALL name the nodes it is missing from, so that a queue present
on three nodes of four is reported as such rather than as present.

A node that is not live SHALL be reported as not evaluated, and SHALL NOT be
reported as missing everything it was not observed to have.

The comparison SHALL read a node's effective configuration through the same source
the system uses to compare configuration between nodes, so that the two features
cannot report different truths about one node.

#### Scenario: A partially applied queue is caught

- **WHEN** a declared queue exists on three of a cluster's four live nodes
- **THEN** it is reported as missing and the report names the fourth node

#### Scenario: A uniformly wrong cluster is caught

- **WHEN** every node of a cluster lacks a declared queue
- **THEN** the drift report names it, even though the nodes agree with each other

#### Scenario: A node that is down is not reported as drift

- **WHEN** a node is not live when drift is evaluated
- **THEN** it is reported as not evaluated rather than as missing every declared
  resource

### Requirement: Reporting undeclared resources is opt-in per cluster

The system SHALL report resources that exist and are not declared only when that
reporting is enabled for the cluster, and SHALL support excluding resources by
pattern.

Reporting undeclared resources SHALL be disabled by default, because a cluster
relying on automatically created queues would otherwise produce a report too large
to be read, and an unread report is worse than none.

#### Scenario: Undeclared resources are silent by default

- **WHEN** drift is evaluated on a cluster where undeclared reporting is not enabled
- **THEN** resources that exist and are not declared produce no findings

#### Scenario: Excluded resources stay out of the report

- **WHEN** undeclared reporting is enabled with an exclusion pattern
- **THEN** resources matching the pattern are not reported

### Requirement: The system never resolves drift on its own

The system SHALL NOT create, change, or destroy any broker resource as a consequence
of a drift finding without an explicit action from an operator or client for that
specific fix.

The system SHALL NOT provide a scheduled, rule-triggered, or otherwise automatic
reconciliation of a cluster to its declaration.

Evaluating drift MAY be scheduled. Acting on it SHALL NOT be.

#### Scenario: Drift is found and nothing is changed

- **WHEN** a scheduled drift evaluation finds a declared queue missing
- **THEN** the finding is reported and no queue is created

#### Scenario: There is no automatic reconciliation to enable

- **WHEN** an operator looks for a way to have the system apply a declaration
  automatically
- **THEN** no such option exists, and the product states that resolving drift is an
  operator action

### Requirement: Resolving a drift finding is an ordinary lifecycle operation

Where a finding can be resolved by creating, destroying, or reconfiguring a
resource, the system SHALL offer that fix as the corresponding cluster lifecycle
operation, carrying all of its behaviour — its preview, its safety cap, its
confirmation, its per-node outcome, and its audit event.

The system SHALL NOT define a separate preview, cap, confirmation, or audit action
for applying a fix. The audit trail SHALL show the lifecycle operation that was
performed, with the drift finding as its context.

Applying a fix SHALL require the permission for the lifecycle operation it performs.
The permission to edit a declaration SHALL NOT confer the ability to change a broker.

#### Scenario: A fix is previewed like any lifecycle command

- **WHEN** an operator chooses to create a missing declared queue from the drift
  report
- **THEN** the preview names the nodes it would be created on, and the operator
  confirms before anything is created

#### Scenario: A fix is audited as the operation it is

- **WHEN** a fix creates a queue
- **THEN** the audit trail records a queue creation, referencing the drift finding,
  rather than a separate kind of event

#### Scenario: Declaring is not the same authority as changing

- **WHEN** an operator may edit a declaration but may not create queues
- **THEN** they can record what should exist and cannot apply the fix

### Requirement: Drift can raise an alert through the existing alerting mechanism

The system SHALL make drift available as a condition the existing alerting
capability can act on, reusing its debounce, its tracking, and its delivery
channels. It SHALL NOT introduce a separate notification path for drift.

#### Scenario: Drift notifies through the existing channels

- **WHEN** an alert rule is configured on drift and drift appears
- **THEN** it is delivered through the configured notification channel, debounced
  like any other alert

### Requirement: The drift report answers whether the cluster is correct in one glance

The drift view SHALL state, before any finding is read, whether the cluster matches
its declaration, and where it does not, how many findings of each kind there are and
when the comparison was last run.

A cluster with no drift SHALL be presented as a resolved state, not as an empty
list, because an empty table is indistinguishable from a comparison that never ran.

A comparison that could not evaluate every node SHALL say so at that same level,
because a report that silently omits an unreachable node overstates how much is
known.

Findings SHALL be grouped by kind, and within a kind ordered so that the ones an
operator is most likely to act on come first.

#### Scenario: A correct cluster says so

- **WHEN** a cluster matches its declaration
- **THEN** the view states that it matches, and when that was last checked, rather
  than showing an empty list

#### Scenario: An incomplete comparison is not presented as a clean result

- **WHEN** a node could not be evaluated
- **THEN** the view says so alongside the summary rather than reporting only what it
  could see

#### Scenario: The scale of drift is apparent before reading findings

- **WHEN** a cluster has drifted
- **THEN** the count of each kind of finding is visible before any finding is opened

### Requirement: A finding shows what differs and, where it can, what would fix it

Each finding SHALL state which resource it concerns, which nodes it applies to, and
what differs — for a configuration difference, the declared value beside the
observed one rather than a description of the difference.

Where a finding can be resolved, the view SHALL offer the lifecycle action that
would resolve it, and that action SHALL carry its ordinary preview and confirmation.
The offer SHALL be absent, with the reason, where the operator lacks the permission
for that action or no action can resolve it.

The action SHALL NOT be presented in a way that suggests the report is applying
itself. It is the operator performing an ordinary operation from a different
starting point.

#### Scenario: A difference is shown as a comparison

- **WHEN** a finding reports a configuration difference
- **THEN** the declared value and the observed value are shown beside each other

#### Scenario: A fix is an ordinary confirmed operation

- **WHEN** an operator chooses to resolve a finding
- **THEN** the ordinary preview and confirmation for that operation are shown before
  anything changes

#### Scenario: An unavailable fix explains itself

- **WHEN** an operator lacks the permission to resolve a finding
- **THEN** the action is not offered and the reason is stated

### Requirement: The report distinguishes a stale declaration from a drifted cluster

The view SHALL show when the declaration was last reviewed, and SHALL surface a
declaration that has not been reviewed for a long time as a possible cause of its
own findings.

A report whose findings are all false because the declaration is out of date is the
expected failure of this feature, and the view SHALL make that hypothesis available
rather than leaving an operator to conclude the tool is wrong.

Regenerating the declaration from the cluster's current state SHALL be reachable
from the report, and SHALL show what it would change before replacing anything.

#### Scenario: An old declaration is visible as a possible cause

- **WHEN** a declaration has not been reviewed for a long time and the report is full
- **THEN** the view surfaces the declaration's age as a possible explanation

#### Scenario: Regenerating shows its effect first

- **WHEN** an operator regenerates a declaration from the report
- **THEN** what would change is shown before the stored declaration is replaced

### Requirement: The report never implies the system will act on it

The drift view SHALL NOT present any control, wording, or setting that suggests the
system can be made to resolve drift on its own. Where an operator looks for such an
option, the view SHALL state that resolving drift is an operator action and why.

#### Scenario: There is no automation to find

- **WHEN** an operator looks in the drift view for a way to have findings resolved
  automatically
- **THEN** no such control exists and the view explains that resolution is an
  operator action
