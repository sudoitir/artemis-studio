## MODIFIED Requirements

### Requirement: The agent surface reflects the installation's enabled features

The agent surface SHALL offer only tools, catalogue entries and help text contributed by enabled features and active plugins. A tool of a disabled feature or inactive plugin SHALL NOT be listed, SHALL NOT be described in the help tool or the tool catalogue resource, and SHALL NOT be referenced by the server instructions. A runbook prompt whose steps name a tool of a disabled feature SHALL omit those steps and say that the feature is disabled on this installation. When a plugin is activated, updated or deactivated, its tools SHALL join, change in, or leave the listed surface and the catalogue without a restart. The server instructions SHALL state that installed plugins may add tools and that the help tool lists them.

#### Scenario: A disabled feature's tools are absent

- **WHEN** the tool list and the tool catalogue are read on an installation with the SQL feature disabled
- **THEN** no SQL tool is listed or described, and the catalogue matches the listed tools exactly

#### Scenario: A runbook states a missing feature instead of naming its tool

- **WHEN** a runbook prompt is retrieved whose steps would use a disabled feature's tool
- **THEN** the prompt omits that step and states the feature is disabled on this installation

#### Scenario: An activated plugin's tools are listed and described

- **WHEN** a plugin contributing one tool is activated
- **THEN** the tool list and the help tool include it without a restart, and the catalogue still matches the listed tools exactly

#### Scenario: A tool call during a plugin update is answered

- **WHEN** a plugin tool is called while its plugin is in brief maintenance
- **THEN** the call returns an error result stating the plugin is updating and when to retry
