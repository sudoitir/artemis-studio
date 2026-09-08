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

#### Scenario: A created divert reports the drift it creates

- **WHEN** an MCP client creates a divert
- **THEN** the result states that the divert persists on the broker and is absent from the
  configuration that broker will next deploy

### Requirement: Capture state is readable through MCP, and capture is not created through it

The system SHALL expose the capture subscriptions of a cluster, with their per-node state,
what they hold, and their estimated loss, to an MCP client through the existing resource
listing tool by extending its resource-kind discriminator.

The system SHALL NOT expose creating a capture subscription as an MCP tool. Capture mutates
broker routing and begins storing application payload, and the disclosure that governs it —
what will be created, on which nodes, and what will be retained — is a decision for a human
at the interface that states it, not a tool call an agent can make on their behalf.

#### Scenario: Capture state is listable

- **WHEN** an MCP client lists resources of the capture-subscription kind
- **THEN** the cluster's capture subscriptions are returned with their per-node state and what they hold

#### Scenario: Capture cannot be created through MCP

- **WHEN** an MCP client looks for a tool that creates a capture subscription
- **THEN** no such tool exists
