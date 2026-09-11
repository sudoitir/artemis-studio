## ADDED Requirements

### Requirement: Broker configuration is readable and applicable through guarded tools

The system SHALL expose a cluster's declared configuration, its drift, its fragment and
its apply history to an MCP client through one read-only tool with a kind discriminator,
and SHALL expose declaring and applying through one mutating tool with an operation
discriminator.

The mutating tool SHALL dry-run by default. A dry-run apply SHALL return the plan — the
steps per node, the hazards in words with their identifiers, and the canary — and SHALL
state which identifiers a real run must acknowledge. A real run SHALL require a
confirmation matching the cluster's name and every high-class hazard identifier the plan
named, and SHALL be refused, listing what is missing, otherwise. A halted run's result
SHALL say in one sentence which node and step stopped it, what was not attempted, that
nothing was rolled back, and that re-running converges.

The permission check, the step cap, the canary-and-halt behaviour and the audit trail
SHALL be the ones the equivalent HTTP request would get.

#### Scenario: A dry run names what to acknowledge

- **WHEN** an MCP client previews applying a declaration whose plan carries a message-loss hazard
- **THEN** the result lists the hazard with its identifier and states that a real run must include it

#### Scenario: A halted run is legible to the agent

- **WHEN** a real run halts on the second node
- **THEN** the result states the node and step, the nodes not attempted, that nothing was rolled back, and that re-running converges

## MODIFIED Requirements

### Requirement: Routing is discoverable and mutable through the existing tool shapes

The system SHALL make diverts and bridges available to an MCP client through the
existing resource listing tool, by extending its resource-kind discriminator rather
than by adding a tool per resource, so the surface stays within its tool-count
budget.

The system SHALL expose divert creation and deletion as one guarded mutating tool,
previewing by default, requiring a confirmation matching the divert's name before
acting.

The result of creating a divert SHALL state in words that the divert persists on the
broker and is absent from the configuration that broker will next deploy, so that an
agent reporting back to a human conveys the consequence and not only the success. It
SHALL NOT describe the divert as temporary or as one that is lost when the broker
restarts.

#### Scenario: Routing is listed through the existing tool

- **WHEN** an MCP client lists resources of the divert kind
- **THEN** the cluster's diverts are returned without a new tool having been added

#### Scenario: A created divert reports the drift it creates

- **WHEN** an MCP client creates a divert
- **THEN** the result states that the divert persists on the broker and is absent from the
  configuration that broker will next deploy
