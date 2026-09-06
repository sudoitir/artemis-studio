## ADDED Requirements

### Requirement: Queue lifecycle is exposed as one guarded tool

The system SHALL expose queue and address lifecycle to an MCP client as a single
tool discriminated by the kind of operation, rather than as one tool per verb, so
that the surface stays within the tool-count budget the MCP capability sets.

The tool SHALL default to previewing rather than acting. Turning the preview off
SHALL additionally require a confirmation argument that exactly matches the name of
the queue or address being operated on, so that a client which ignores every
protocol hint still cannot destroy a resource by accident.

The tool SHALL delegate to the same service the HTTP API uses, inheriting its
permission checks, its safety cap, and its audit trail. It SHALL NOT implement
authorization, capping, or auditing of its own.

The per-node outcome SHALL be returned to the client in full, so that an agent can
tell a fully applied command from a partially applied one.

#### Scenario: The default is a preview

- **WHEN** an MCP client invokes the lifecycle tool without specifying otherwise
- **THEN** the operation is previewed and no broker is mutated

#### Scenario: Acting without a matching confirmation is refused

- **WHEN** an MCP client turns off the preview for a destructive kind and supplies a
  confirmation that does not match the target's name
- **THEN** the call is refused and nothing is mutated

#### Scenario: The tool inherits the caller's permissions

- **WHEN** an MCP client whose token lacks the destroy permission invokes a destroy
- **THEN** the call is refused on the same basis as the equivalent HTTP request, and
  reveals no more about the cluster than that request would

#### Scenario: A partial application is legible to the agent

- **WHEN** a lifecycle tool call applies on some nodes and fails on others
- **THEN** the result reports the per-node outcome rather than a single success or
  failure
