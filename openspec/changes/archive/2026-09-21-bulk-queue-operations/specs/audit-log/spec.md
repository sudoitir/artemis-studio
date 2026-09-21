## ADDED Requirements

### Requirement: An audit event can belong to a parent event

An audit event MAY name another audit event as its parent, when it was issued as one part
of a larger operation such as a bulk run. The parent SHALL be committed before any of its
children. A child SHALL carry everything an ordinary audit event carries. Its parent link
adds to that and replaces nothing.

The audit trail SHALL show, for a child, the parent it belongs to. For a parent, it SHALL
show its children, reachable from the parent.

#### Scenario: A child event links to its parent

- **WHEN** an operator opens the audit event for a queue deleted by a bulk run
- **THEN** the event shows the bulk run's event it belongs to, and following that link
  shows the run's event with all of its queue events
