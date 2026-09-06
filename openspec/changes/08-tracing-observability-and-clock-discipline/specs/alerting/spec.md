## ADDED Requirements

### Requirement: Clock skew is an alertable cluster-state condition

The system SHALL provide a cluster-state alert condition for a clock that
disagrees with its own beyond the configured tolerance, evaluated from the same
polled state as the other cluster-state conditions and never from a metric sample.

Its subjects SHALL be the individual nodes, plus one distinct subject representing
the system's own host for the case where every measured node disagrees the same
way, so that each is tracked and silenced independently.

Only nodes whose clocks have actually been measured SHALL be evaluated: an
unmeasured node SHALL NOT contribute to resolving the condition.

A rule for this condition SHALL be created for each cluster at warning severity,
and SHALL be an ordinary rule — editable, silenceable and deletable like any other.

#### Scenario: A skewed node fires

- **WHEN** one node's clock disagrees beyond tolerance for longer than the rule's
  debounce
- **THEN** the alert fires with that node as the subject

#### Scenario: The host itself is named

- **WHEN** every measured node disagrees in the same direction beyond tolerance
- **THEN** the alert fires against the subject representing the system's own host,
  not against each node

#### Scenario: An unmeasured node cannot resolve the alert

- **WHEN** a node's clock has never been measured
- **THEN** it is not evaluated, and its absence does not resolve a firing alert
