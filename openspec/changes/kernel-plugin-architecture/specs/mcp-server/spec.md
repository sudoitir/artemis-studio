## ADDED Requirements

### Requirement: The agent surface reflects the installation's enabled features

The agent surface SHALL offer only tools, catalogue entries and help text contributed by enabled features. A tool of a disabled feature SHALL NOT be listed, SHALL NOT be described in the help tool or the tool catalogue resource, and SHALL NOT be referenced by the server instructions. A runbook prompt whose steps name a tool of a disabled feature SHALL omit those steps and say that the feature is disabled on this installation.

#### Scenario: A disabled feature's tools are absent

- **WHEN** the tool list and the tool catalogue are read on an installation with the SQL feature disabled
- **THEN** no SQL tool is listed or described, and the catalogue matches the listed tools exactly

#### Scenario: A runbook states a missing feature instead of naming its tool

- **WHEN** a runbook prompt is retrieved whose steps would use a disabled feature's tool
- **THEN** the prompt omits that step and states the feature is disabled on this installation

### Requirement: The agent surface itself can be disabled

The agent surface SHALL be a feature that an operator can disable at startup. When it is disabled, its endpoint SHALL NOT exist, and a request to it SHALL receive `404`. API tokens SHALL continue to authenticate the REST API.

#### Scenario: Disabled agent surface has no endpoint

- **WHEN** the agent surface is disabled and a client with a valid API token calls its endpoint
- **THEN** the response is `404`

#### Scenario: Tokens still work for the REST API

- **WHEN** the agent surface is disabled
- **THEN** a valid API token still authenticates REST API requests within its grants
