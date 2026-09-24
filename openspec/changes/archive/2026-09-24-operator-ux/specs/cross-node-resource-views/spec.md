## ADDED Requirements

### Requirement: A listing's filter matches the identifiers other views link with

The filter of each live listing SHALL match:
- **connections**: remote address, client identifier and connection identifier;
- **sessions**: session identifier, connection identifier and user;
- **consumers**: queue name and session identifier;
- **producers**: address, producer name and session identifier.

Each filter's label SHALL name what it matches.

#### Scenario: Following a session to its connection

- **WHEN** an operator follows the connection link of a session row
- **THEN** the connections listing shows that connection

### Requirement: A row's close outcome survives the row

A close started from a row SHALL keep its preview, confirmation and outcome on screen even
when the listing refreshes and the row is no longer in it. It SHALL remain until the
operator dismisses it.

#### Scenario: Closing a connection

- **WHEN** an operator confirms closing a connection and the next listing no longer
  contains it
- **THEN** the per-node outcome of the close stays visible
