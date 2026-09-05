# audit-log Specification

## Purpose

Defines how Artemis Studio records who did what to which broker and with what
outcome — the real, authenticated actor an action is attributed to, the
lifecycle of an audit row within the command's transaction, and the filtered
read an operator uses to review the trail.

## Requirements

### Requirement: Every mutating call is attributed to a resolved actor

For every mutating operation the system SHALL resolve an actor and record, on
the audit row, the actor's username, the resolved user id when the actor is an
authenticated user, the request source IP, and a request id. When an
authenticated principal is present its username and user id SHALL be used;
otherwise the literal `anonymous` SHALL be recorded with no user id. The
request id SHALL be taken from an `X-Request-Id` request header when present
and otherwise generated. Operations originating from the scrape scheduler
SHALL be attributed to `system` with no user id.

#### Scenario: Authenticated operator is attributed by identity

- **WHEN** a mutation is made by an authenticated user
- **THEN** the audit row records that user's username and user id, the
  caller's source IP, and a request id

#### Scenario: Unauthenticated operator is anonymous

- **WHEN** an audited action occurs with no authenticated principal
- **THEN** the audit row records `anonymous`, the caller's source IP, and a
  request id, with no user id

#### Scenario: Failed login is anonymous

- **WHEN** a login attempt fails
- **THEN** the resulting audit row records `anonymous` with no user id

#### Scenario: Supplied request id is preserved

- **WHEN** a mutation request carries an `X-Request-Id` header
- **THEN** that value is recorded on the audit row

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

### Requirement: Identity and governance actions are audited

Creating, updating, disabling, or deleting a user, role, permission grant,
environment, API token, or OIDC role mapping, and every login, logout, and
password change, SHALL follow the same pending-then-outcome audit lifecycle as
any other mutation.

#### Scenario: A role grant change is audited

- **WHEN** an administrator grants or revokes a role from a user
- **THEN** an audit row is created for that action, following the pending-then-
  outcome lifecycle

#### Scenario: A login attempt is audited

- **WHEN** a user attempts to log in, whether the attempt succeeds or fails
- **THEN** an audit row is created recording the attempt and its outcome

### Requirement: The audit trail's actor filter is a real user, not free text

The audit read SHALL allow filtering by a specific user account, in addition
to the existing action, outcome, and time-range filters.

#### Scenario: Filter by user

- **WHEN** an operator filters the audit trail to one user account
- **THEN** only events attributed to that user's account are returned
