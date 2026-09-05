## MODIFIED Requirements

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

## ADDED Requirements

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
