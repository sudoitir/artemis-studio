## MODIFIED Requirements

### Requirement: An audit row is created before the broker call and updated with the outcome

The system SHALL insert the `audit_event` row with a pending outcome before
issuing the broker call, and update the same row — in the same transaction — to
a success outcome with the affected count, or to a failure outcome with the
error message, after. The row SHALL record the action, the target type and name,
the cluster and node, the parameters, and whether it was a dry run. No mutating
path SHALL commit without its audit row. Creating, updating, deleting, or testing
an alert rule or a notification channel SHALL follow this same pattern.
Machine-generated alert firings, resolutions, and notification delivery attempts
SHALL NOT create an audit event.

#### Scenario: Outcome transitions from pending to success

- **WHEN** a mutation completes without error
- **THEN** its audit row is committed with a success outcome and the affected
  count

#### Scenario: Outcome transitions from pending to failure

- **WHEN** the broker call throws
- **THEN** its audit row is committed with a failure outcome and the error, and
  the state change is rolled back with it as one unit

#### Scenario: Dry run is recorded

- **WHEN** a mutation is a dry run
- **THEN** the audit row is marked as a dry run

#### Scenario: Alert rule changes are audited

- **WHEN** an operator creates, updates, deletes, or tests an alert rule or a
  notification channel
- **THEN** an audit row is created for that operator action, following the same
  pending-then-outcome lifecycle as any other mutation

#### Scenario: An alert firing does not create an audit row

- **WHEN** an alert rule transitions to firing or resolves, or a notification
  delivery attempt is made
- **THEN** no audit event is created for it
