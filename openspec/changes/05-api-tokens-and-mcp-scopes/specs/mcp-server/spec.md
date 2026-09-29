## ADDED Requirements

### Requirement: A token can be restricted to named MCP tools
A token SHALL optionally carry an allow-list of MCP tool names; calls to other tools SHALL be denied without revealing them.

#### Scenario: Tool outside the list
- **WHEN** a restricted token calls another tool
- **THEN** the call is denied and audited

#### Scenario: Discovery
- **WHEN** a restricted token lists tools
- **THEN** only allowed tools are listed

### Requirement: MCP can be made read-only for the whole installation
An administrator SHALL be able to set the agent surface read-only for the whole installation; a mutating call SHALL then be refused. A read-only token SHALL remain a token granted only read permissions, with no second read-only flag.

#### Scenario: Global read-only
- **WHEN** global read-only is on
- **THEN** no token can perform a mutating primitive, even with a dry-run confirmation

#### Scenario: Read-only token
- **WHEN** a token granted only read permissions attempts a mutation through MCP
- **THEN** it is refused by its grants and audited

#### Scenario: Mutating tools hidden
- **WHEN** global read-only is on and an agent lists tools
- **THEN** mutating tools are not offered

### Requirement: Every agent action is audited with token and tool
Each MCP call, reads included, SHALL produce an audit event naming the token, the tool, the target and the outcome. Today only mutations are audited.

#### Scenario: Read call
- **WHEN** an agent reads through a tool
- **THEN** the audit trail records token and tool name
