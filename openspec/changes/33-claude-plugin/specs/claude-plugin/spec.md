## ADDED Requirements

### Requirement: The repository contains an installable Claude Code plugin
The repository SHALL contain a Claude Code plugin, in a directory of its own, that installs from a marketplace entry and loads its skills and connector.

#### Scenario: Install
- **WHEN** a user adds the marketplace and installs the plugin
- **THEN** its skills and connector are available

#### Scenario: Invalid plugin
- **WHEN** the manifest is invalid
- **THEN** validation in CI fails

### Requirement: Skills cover building applications on Artemis
The plugin SHALL provide skills for addressing and routing, client configuration and troubleshooting, whose guidance is correct for the supported Artemis range and states when it does not apply.

#### Scenario: A routing question
- **WHEN** a developer asks how to route to multiple consumers
- **THEN** the skill gives Artemis addressing and routing-type guidance

#### Scenario: Unsupported broker
- **WHEN** the question concerns a broker outside the supported range
- **THEN** the skill says so and does not guess

### Requirement: The plugin connects to Studio's MCP server without storing secrets
The connector SHALL let the user point to their Studio and authenticate with an API token supplied at install or run time, and SHALL NOT store a token in the repository or in plugin files.

#### Scenario: Connect
- **WHEN** a user configures their Studio address and token
- **THEN** the assistant can call Studio's MCP tools within the token's scopes

#### Scenario: Leak check
- **WHEN** the repository is scanned
- **THEN** no token or credential is present

#### Scenario: Least privilege by default
- **WHEN** a user follows the documented setup
- **THEN** it mints a token limited to read permissions and to the tools the skills use, and the skills work with it

### Requirement: The plugin is versioned with Studio
Each Studio release SHALL publish the plugin at the same version, and the plugin SHALL name the Studio versions it works with.

#### Scenario: Release
- **WHEN** Studio is released
- **THEN** the plugin version matches and its compatibility is stated

### Requirement: The plugin is documented
The documentation SHALL describe installation, the skills, the connector and the token scopes it needs.

#### Scenario: A new user reads it
- **WHEN** they follow the guide
- **THEN** they can install the plugin and run a Studio tool from the assistant
