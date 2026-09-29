## ADDED Requirements

### Requirement: A token can be restricted to named MCP tools
A token SHALL optionally carry an allow-list of MCP tool names; calls to other tools SHALL be denied without revealing them.

#### Scenario: Tool outside the list
- **WHEN** a restricted token calls another tool
- **THEN** the call is denied and audited

#### Scenario: Discovery
- **WHEN** a restricted token lists tools
- **THEN** only allowed tools are listed

### Requirement: MCP can be made read-only globally and per token
An administrator SHALL be able to set MCP read-only for the whole installation, and a token SHALL be able to be read-only; a mutating call under either SHALL be refused.

#### Scenario: Global read-only
- **WHEN** global read-only is on
- **THEN** no token can perform a mutating primitive, even with a dry-run confirmation

#### Scenario: Per-token read-only
- **WHEN** a read-only token attempts a mutation
- **THEN** it is refused and audited

### Requirement: Every agent action is audited with token and tool
Each MCP call SHALL produce an audit event naming the token, the tool, the target and the outcome.

#### Scenario: Read call
- **WHEN** an agent reads through a tool
- **THEN** the audit trail records token and tool name
