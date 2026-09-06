## ADDED Requirements

### Requirement: Routing is discoverable and mutable through the existing tool shapes

The system SHALL make diverts and bridges available to an MCP client through the
existing resource listing tool, by extending its resource-kind discriminator rather
than by adding a tool per resource, so the surface stays within its tool-count
budget.

The system SHALL expose divert creation and deletion as one guarded mutating tool,
previewing by default, requiring a confirmation matching the divert's name before
acting.

The result of creating a divert SHALL state in words that the divert will be lost
when the broker restarts, so that an agent reporting back to a human conveys the
consequence and not only the success.

#### Scenario: Routing is listed through the existing tool

- **WHEN** an MCP client lists resources of the divert kind
- **THEN** the cluster's diverts are returned without a new tool having been added

#### Scenario: A created divert reports its impermanence

- **WHEN** an MCP client creates a divert
- **THEN** the result states that it will not survive a broker restart
