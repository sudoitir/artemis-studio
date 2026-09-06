## ADDED Requirements

### Requirement: Drift is readable by an agent and actionable only through the guarded tools

The system SHALL expose a cluster's drift report to an MCP client as a read-only
tool, reporting each finding, its kind, and the nodes it applies to.

The system SHALL NOT expose any tool that resolves drift as such. An agent SHALL
resolve a finding by invoking the ordinary guarded lifecycle tool for the operation
required, subject to its preview default, its confirmation requirement, its safety
cap and its audit trail.

#### Scenario: An agent can read drift

- **WHEN** an MCP client requests a cluster's drift report
- **THEN** the findings are returned, classified and attributed to nodes, with no
  mutation performed

#### Scenario: There is no shortcut from a finding to a change

- **WHEN** an MCP client attempts to resolve drift directly
- **THEN** no such tool exists, and any resolution goes through the guarded lifecycle
  tool with its preview and confirmation intact
