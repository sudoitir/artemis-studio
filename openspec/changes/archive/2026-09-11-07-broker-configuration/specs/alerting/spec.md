## ADDED Requirements

### Requirement: Configuration drift is an alertable cluster-state condition

The system SHALL provide a cluster-state alert condition that is active for a node whose
last configuration evaluation found drift, evaluated from the recorded per-node state and
never from a metric sample. Its subjects SHALL be the individual nodes, so that each is
tracked and silenced independently. A node that is not evaluated or unreachable SHALL
NOT contribute to resolving the condition.

The condition SHALL ship as a rule template, not a seeded rule, because a cluster without
a declaration has nothing to drift from.

#### Scenario: A drifted node fires

- **WHEN** an evaluation records drift on one node for longer than the rule's debounce
- **THEN** the alert fires with that node as the subject

#### Scenario: An unevaluated node cannot resolve the alert

- **WHEN** a node has not been evaluated since it last drifted
- **THEN** its absence from the latest evaluation does not resolve a firing alert
