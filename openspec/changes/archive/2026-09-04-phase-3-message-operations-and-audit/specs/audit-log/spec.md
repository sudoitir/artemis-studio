## Purpose

Defines how Artemis Studio records who did what to which broker and with what
outcome — the actor it attributes an action to before authentication exists, the
lifecycle of an audit row within the command's transaction, and the filtered
read an operator uses to review the trail.

## ADDED Requirements

### Requirement: Every mutating call is attributed to a resolved actor

For every mutating operation the system SHALL resolve an actor and record, on
the audit row, the actor's username, the request source IP, and a request id.
When an authenticated principal is present its name SHALL be used; otherwise the
literal `anonymous` SHALL be recorded. The request id SHALL be taken from an
`X-Request-Id` request header when present and otherwise generated. Operations
originating from the scrape scheduler SHALL be attributed to `system`.

#### Scenario: Unauthenticated operator is anonymous

- **WHEN** a mutation is made with no authenticated principal
- **THEN** the audit row records `anonymous`, the caller's source IP, and a
  request id

#### Scenario: Supplied request id is preserved

- **WHEN** a mutation request carries an `X-Request-Id` header
- **THEN** that value is recorded on the audit row

### Requirement: An audit row is created before the broker call and updated with the outcome

The system SHALL insert the `audit_event` row with a pending outcome before
issuing the broker call, and update the same row — in the same transaction — to
a success outcome with the affected count, or to a failure outcome with the
error message, after. The row SHALL record the action, the target type and name,
the cluster and node, the parameters, and whether it was a dry run. No mutating
path SHALL commit without its audit row.

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

### Requirement: The audit trail is readable with filters

The system SHALL expose a paged read of audit events for a cluster, filterable
by actor username, action, outcome, and a time range, ordered most-recent first.
Each returned event SHALL include its timestamp, actor, action, target, affected
count, outcome, dry-run flag, parameters, and error when present.

#### Scenario: Filter by action and outcome

- **WHEN** an operator lists audit events filtered to a single action and the
  failure outcome
- **THEN** only failed events for that action are returned, newest first

#### Scenario: Time range narrows the result

- **WHEN** a from/to range is supplied
- **THEN** only events whose timestamp falls in the range are returned

### Requirement: The audit screen shows outcome without relying on colour alone

The frontend SHALL present each audit event's outcome with a status word as well
as a colour, and SHALL make the parameters and any error inspectable per row.

#### Scenario: Outcome is legible without colour

- **WHEN** the audit list renders a failed event
- **THEN** the row shows the word for the failure outcome, not only a coloured mark

#### Scenario: Parameters are inspectable

- **WHEN** an operator expands an audit row
- **THEN** the recorded parameters and error text are shown
