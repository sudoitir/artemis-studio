## ADDED Requirements

### Requirement: Connection, session and consumer rows offer the action their finding implies

The connections, sessions and consumers views SHALL offer, on each row, the close
operation that applies to it, so that a finding and the action it implies are not
separated by a change of tool.

Where the system marks a consumer as slow, that marking SHALL lead to the same
action, so an operator does not have to re-find the row in another view.

A row is served from a cache and may be stale; acting on a row whose target has
since gone SHALL be presented as already closed rather than as a failure.

#### Scenario: The action is on the row

- **WHEN** an operator views the consumers of a cluster
- **THEN** each row offers closing its connection, subject to permission

#### Scenario: A slow-consumer finding leads to the action

- **WHEN** a consumer is marked slow
- **THEN** the operator can act on that finding without leaving the view it appears
  in

#### Scenario: Acting on a stale row is not an error

- **WHEN** an operator closes a connection from a row whose connection has since
  disconnected
- **THEN** the view reports it as already closed

### Requirement: A close is confirmed against something the operator can recognise

The confirmation for closing a connection SHALL be typed against an identifier a
human can recognise — the client identifier, or the remote address where no client
identifier is reported — and SHALL NOT be typed against the opaque connection
identifier, which an operator cannot verify they have the right one from.

The confirmation SHALL show, alongside it, the user, the node, and the number of
sessions and consumers that will be closed with it, so the scale of the disconnect
is visible before it is armed.

Where the target's in-flight message count is known, the confirmation SHALL state
it together with the consequence that those messages return to their queue with an
increased delivery count.

#### Scenario: The operator confirms against a recognisable name

- **WHEN** an operator is asked to confirm closing a connection
- **THEN** the value to type is the client identifier or remote address, not the
  opaque connection identifier

#### Scenario: The scale of the disconnect is shown

- **WHEN** a connection carrying several sessions and consumers is about to be closed
- **THEN** the confirmation states how many of each will be closed with it

### Requirement: The row action reflects that the row may be stale

Because rows are served from a cache, the connections, sessions and consumers views
SHALL present the age of what they are showing, and SHALL treat a close whose target
has since gone as a completed outcome rather than an error — reporting it in the
same place the successful outcome would appear, not as a failure notification.

After a close, the affected row SHALL be reconciled without requiring the operator
to reload the view.

#### Scenario: Staleness is visible

- **WHEN** an operator views connections
- **THEN** how current the data is, is apparent

#### Scenario: An already-gone target reads as done, not failed

- **WHEN** an operator closes a connection that has already disconnected
- **THEN** the outcome appears as completed, in the same place a successful close
  would appear
