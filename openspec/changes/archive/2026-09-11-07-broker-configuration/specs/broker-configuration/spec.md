## Purpose

Defines how a cluster's configuration — its addresses and queues, address settings,
security settings and diverts — is declared in Artemis Studio, imported from and exported
to `broker.xml`, applied to every live node over the management API without taking a
whole cluster down at once, and continuously compared against what each node is
actually running. Absorbs the `desired-state` capability change 05 proposed.

## ADDED Requirements

### Requirement: An operator can declare what a cluster is configured to be

The system SHALL allow an authorized operator to declare, per cluster, the addresses and
their queues, the address settings by match pattern, the security settings by match
pattern, and the diverts the cluster is expected to have, as one typed document.

The declaration SHALL persist independently of any broker-derived cache and SHALL survive
the deletion of such a cache. Every save SHALL create a new revision recording who saved
it, when, and from what source, and the history of revisions SHALL be readable.

A save SHALL name the revision it edited and SHALL be refused when that revision is no
longer current, stating what to do next, so that two operators cannot silently overwrite
each other.

#### Scenario: Clearing broker-derived caches does not affect a declaration

- **WHEN** the broker-derived resource cache is cleared
- **THEN** the cluster's declaration and its revisions are unchanged

#### Scenario: A stale save is refused

- **WHEN** an operator saves an edit made against revision 7 after another operator has saved revision 8
- **THEN** the save is refused, revision 8 remains current, and the refusal says to reload and re-apply the edit

### Requirement: The declaration records intent and is not authoritative for brokers

The declaration SHALL be understood and presented as the system's record of what an
operator expects a cluster to be configured as. Broker configuration remains
authoritative for what a broker does. Drift SHALL be reported as a disagreement between
the declaration and the observed cluster, never as the broker being wrong.

Editing a declaration SHALL NOT change any broker.

#### Scenario: Editing the declaration changes nothing on a broker

- **WHEN** an operator edits and saves a cluster's declaration
- **THEN** no broker is contacted and no broker state changes

### Requirement: A declaration can be adopted from the running cluster

The system SHALL build a declaration from what a cluster's live nodes are running, for
review before it is saved, so that an operator adopting the feature does not enter it by
hand. Where live nodes disagree, the adopted declaration SHALL list the disagreement
rather than pick a value silently.

The broker reports the settings an address resolves to, never the match patterns its
configuration declares. Adoption SHALL therefore seed the catch-all match with every
key the broker reports for it, and one entry per observed address carrying the keys
whose resolved value differs from the catch-all, and SHALL state that the original
patterns could not be read. An operator MAY then merge address entries into the
pattern they know.

#### Scenario: Adoption seeds what reproduces the observed state

- **WHEN** an operator adopts a declaration from a cluster where `orders.in` resolves three keys differently from `#`
- **THEN** the adopted document carries `#` with every reported key and an `orders.in` entry with those three keys, and says that match patterns cannot be read from a broker

#### Scenario: Node disagreement is listed

- **WHEN** two live nodes report different values for one key of one match
- **THEN** the adopted declaration names both nodes and both values for that key instead of choosing one

### Requirement: XML is an interchange format for the declaration

The system SHALL parse a pasted `broker.xml` or `<core>` fragment into the declaration's
sections and SHALL render a declaration as a `<core>` fragment. The parsed result SHALL
be previewed before it becomes a revision, stating per section what was recognised as
added, changed or unchanged.

Every element the parser does not support — including any `<core>` setting the management
API cannot apply — SHALL be listed by its path as unsupported and not applied. Nothing
SHALL be dropped silently. A value of the form `${…}` SHALL be rejected, naming the
element, because the system cannot resolve it.

The rendered fragment SHALL be escaped and SHALL round-trip through the parser without
loss for the supported sections.

#### Scenario: A static setting is listed, not dropped

- **WHEN** an operator imports a fragment containing `<global-max-size>` and an `<address-settings>` section
- **THEN** the address settings are recognised and `global-max-size` is listed as unsupported and not applied

#### Scenario: A placeholder is refused by name

- **WHEN** an imported `<page-limit-bytes>` holds `${PAGE_LIMIT}`
- **THEN** the import reports the element and states that the value must be concrete

#### Scenario: Export round-trips

- **WHEN** a declaration is exported and the result is imported
- **THEN** the imported document equals the exported one and no unsupported element is reported

### Requirement: Each cluster chooses how its configuration is applied

A cluster's declaration SHALL carry an apply mode: managed by the system, or managed
outside it. When managed by the system, the primary action SHALL be to preview and apply
the declaration to the brokers. When managed outside it, the primary action SHALL be to
copy the generated fragment, and the apply control SHALL remain visible, disabled, with
the reason stated — never hidden.

Drift SHALL be evaluated in both modes, so a cluster whose configuration is deployed by
other tooling learns when that deployment matches the declaration.

The system SHALL NOT write any broker configuration file and SHALL NOT ask a broker to
reload one.

#### Scenario: A config-managed cluster keeps its apply control, disabled

- **WHEN** an operator views a cluster whose configuration is managed outside the system
- **THEN** the apply control is present and disabled, its reason is readable without a pointer, and copying the fragment is the primary action

#### Scenario: Nothing reloads a file

- **WHEN** an operator has copied the fragment for a config-managed cluster
- **THEN** the system offers no action that would reload or write a broker's configuration file

### Requirement: An apply is a plan, computed from what each node is running

Previewing an apply SHALL read every live node once, in a bounded number of batched
requests per node (at most two, never one per declared item) under the per-node rate
limiter, and SHALL produce, per node, the ordered list of steps
whose observed state differs from the declaration. A step whose observed state already
matches SHALL be reported as already satisfied and SHALL issue no write.

Steps SHALL be ordered so that anything a later step depends on exists first: addresses,
then queues, then address settings, then security settings, then diverts; removals after
additions, in reverse order.

For an address setting the plan SHALL show, per match and per node, the value before and
after for every key the broker reports — not only the declared keys — because the broker
replaces the entry for a match rather than merging into it.

A preview SHALL make no mutating call to any broker.

#### Scenario: A matching node has no steps

- **WHEN** a node already runs everything the declaration says
- **THEN** every step for that node is reported as already satisfied and nothing is written to it

#### Scenario: An undeclared key that will change is shown

- **WHEN** the declaration sets three keys on a match the node has twelve keys set on
- **THEN** the plan shows the nine keys that will revert to inherited values, with their current values

### Requirement: Hazards are named before anything is written

The plan SHALL classify each step's hazards and state each one in words with its class.
At least the following SHALL be recognised:

- a policy that discards or refuses messages (`DROP`, `FAIL`) or stalls producers (`BLOCK`), on a match covering an observed address;
- a size or count limit lower than what an observed address under the match already holds, naming the node and address where the policy would trigger at once;
- a key that will change although the declaration does not set it;
- a security match that covers the management or notifications address, or is `#` or `*`, which may revoke the system's own access;
- an address or security match of `#` or `*`;
- an exclusive divert on an address with observed queues;
- replacing an existing divert, which is a delete and a create with a gap between them;
- a change to a dead-letter or expiry address, or enabling automatic deletion, on a match with observed resources;
- removing an item the system applied, and — only when opted into — removing one it did not.

A real run SHALL require every high-class hazard to be acknowledged by its identifier and
SHALL be refused, listing the missing ones, otherwise. The user interface SHALL arm a real
run by typing the cluster's name; acknowledgement alone SHALL NOT arm it.

The system SHALL refuse to apply an unknown key, because the broker accepts one and does
nothing, and SHALL validate a page size against a match's merged maximum size, because the
broker refuses the pair after the fact.

#### Scenario: A loss policy must be acknowledged

- **WHEN** a plan sets `address-full-policy` to `DROP` on `orders.#` while `orders.in` exists, and the operator applies without acknowledging that hazard
- **THEN** the run is refused and the response names the hazard identifier that is missing

#### Scenario: A limit already exceeded is named with its node

- **WHEN** the declaration lowers `max-size-bytes` for `orders.#` below what `orders.in` holds on `broker-2`
- **THEN** the plan states that the policy would trigger immediately on `broker-2` for `orders.in`

#### Scenario: An unknown key never reaches a broker

- **WHEN** a declaration contains a key the catalogue does not know
- **THEN** validation fails naming the key and its section, and no plan is produced

### Requirement: An apply runs canary-first, verifies, and halts on the first failure

A real run SHALL apply every step to one live node first, SHALL re-read that node in the
same bounded batched read and verify each step against what was declared, and only then continue
to the next live node. The canary SHALL be named in the plan and MAY be chosen by the
operator.

The first failed step SHALL stop the run: the remaining steps on that node and every
remaining node SHALL be reported as not attempted. A read-back that disagrees with what
was written SHALL count as a failure. Nothing SHALL be rolled back, and nothing beyond the
failed node SHALL be touched.

Where a security change could revoke the system's own access, the verification read
SHALL be required to succeed, and its failure SHALL be reported as the system being unable
to revert what it can no longer reach.

Every outcome SHALL be reported per node and per step, in words: would apply, applied,
already satisfied, failed with the reason, not attempted, or skipped because the node is
not live. A backup that is not live SHALL be stated to inherit the change once active,
never reported as missing it.

Re-running the same revision after a halt SHALL converge: steps already satisfied report
so, and the failed and not-attempted ones are attempted again.

#### Scenario: A canary failure touches nothing else

- **WHEN** the third step fails on the canary of a three-node cluster
- **THEN** the canary's remaining steps and both other nodes are reported as not attempted, and no broker other than the canary was written to

#### Scenario: A halt is legible and repeatable

- **WHEN** a run halts on the second node
- **THEN** the result names the node and step, states that nothing was rolled back and the third node was not attempted, and re-running reports the first node's steps as already satisfied

### Requirement: The system removes only what it applied and never destroys a queue or address

The system SHALL record every item it applies as its own. A removal step SHALL be
produced for an item absent from the declaration only when the system applied it.

An item present on a node and absent from the declaration SHALL be reported as
undeclared. Removing it SHALL require an explicit opt-in for that plan and SHALL carry its
own hazard, stating that if the broker's configuration file also declares it, the removal
reverts on the next restart and the system cannot tell.

An apply SHALL NOT destroy a queue or an address under any option. Destroying data is the
queue's own operation with its own safety cap.

#### Scenario: An unowned setting is reported, not removed

- **WHEN** a match exists on a node, is absent from the declaration, and was not applied by the system
- **THEN** it appears as undeclared and the plan contains no removal step for it

#### Scenario: A declared-away queue survives

- **WHEN** a queue with messages is removed from the declaration and the declaration is applied
- **THEN** the queue is untouched and the report says where it can be deleted

### Requirement: Drift is classified per node and never resolved on its own

The system SHALL compare the current revision against what each live node reports, in one
batched read per node, and SHALL classify each finding as declared but missing on named
nodes, present but configured differently — declared beside observed — or present and
not declared. A node that is not live SHALL be reported as not evaluated; a node that
cannot be read SHALL be reported as unreachable with the classified reason. Neither SHALL
be reported as missing what was not observed.

Reporting undeclared resources SHALL be enabled per cluster, off by default, with
exclusion patterns, because a cluster relying on automatically created queues would
otherwise produce a report too large to read.

Evaluation MAY be scheduled, at an interval held in the settings registry. Acting on a
finding SHALL NOT be: the system SHALL NOT create, change or remove any broker resource
as a consequence of a finding without an explicit apply for it, and SHALL NOT provide a
scheduled, rule-triggered or otherwise automatic reconciliation.

A matching cluster SHALL be presented as a resolved state — every live node matching the
named revision, with when it was evaluated — never as an empty table.

#### Scenario: A uniformly wrong cluster is caught

- **WHEN** every live node lacks a declared address setting
- **THEN** the finding names the match and every node, even though the nodes agree with each other

#### Scenario: A drifted cluster stays drifted until an operator acts

- **WHEN** a scheduled evaluation finds a declared divert missing
- **THEN** the finding is recorded and no divert is created

### Requirement: Applies are audited once each and readable afterwards

The system SHALL record one audit event per apply command, dry runs included and flagged,
capturing the actor, the cluster, the revision, the acknowledged hazards, whether it was a
dry run, and the per-node, per-step outcome. A run that halted or failed on any node
SHALL be recorded as failed with the detail preserved. Saving a declaration SHALL be
audited as an edit that contacted no broker.

The history of applies SHALL be readable with the outcome word, the canary, the counts,
and a link to the audit event.

#### Scenario: A halted run is one failed audit event

- **WHEN** a run applies four steps on the canary and halts on the second node
- **THEN** a single audit event records the command as failed and its detail names every node and step

### Requirement: One apply per cluster at a time, against the plan that was previewed

The system SHALL refuse a second apply for a cluster while one is running, stating so. A
real run SHALL name the plan it previewed and SHALL be refused when the plan computed at
run time differs, so that what was confirmed is what runs.

#### Scenario: The cluster moved after the preview

- **WHEN** a node's settings change between the preview and the confirmation
- **THEN** the run is refused and the operator is told to preview again
