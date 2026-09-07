## ADDED Requirements

### Requirement: Connection control is exposed as one destructive tool

The system SHALL expose closing a connection, a session, and an address's consumers
to an MCP client as a single tool discriminated by the kind of operation, declared
as destructive and as not idempotent.

The tool SHALL default to previewing for the kind that affects an unbounded number
of connections, and turning the preview off SHALL require a confirmation argument
matching the client identifier or address being targeted.

The tool SHALL delegate to the same service the HTTP API uses and SHALL NOT
implement authorization, capping, or auditing of its own.

An agent SHALL be able to tell from the result whether a connection was closed or
was already gone.

#### Scenario: The tool declares what it does

- **WHEN** an MCP client lists the available tools
- **THEN** the connection-control tool is declared destructive and not idempotent

#### Scenario: Acting without a matching confirmation is refused

- **WHEN** an MCP client turns off the preview and supplies a confirmation that does
  not match the target
- **THEN** the call is refused and no connection is closed

#### Scenario: An already-gone target is distinguishable

- **WHEN** an MCP client closes a connection that has already disconnected
- **THEN** the result reports it as already gone rather than as closed or as an error
