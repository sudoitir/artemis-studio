## MODIFIED Requirements

### Requirement: MCP calls authenticate as a personal API token and carry that identity

The MCP endpoint SHALL require authentication and SHALL accept the same personal API token
credential as the rest of the API. An unauthenticated call SHALL be refused before any
primitive executes.

An authenticated call SHALL execute under the token owner's identity, and every permission
check, cluster-scope check and audit attribution that applies to the equivalent human-facing
operation SHALL apply identically. The surface SHALL NOT introduce a second credential
store, a second authorization model, or any permission that exists only for MCP. A token's
tool allow-list and the installation's read-only mode only narrow what the token's grants
already permit.

Every tool call, reads included, SHALL produce an audit event naming the token's owner, the
token, the tool, the target cluster when there is one, and the outcome. The audit events of the
operations a call performs SHALL be attached to that call's event.

#### Scenario: Unauthenticated call is refused

- **WHEN** the MCP endpoint is called with no credential
- **THEN** the request is rejected as unauthenticated and no primitive runs

#### Scenario: A token's power is bounded by its owner

- **WHEN** a primitive is invoked with a token whose effective grants do not permit the
  underlying operation
- **THEN** the call fails and no broker or Studio state is changed

#### Scenario: Mutations are audited under the owner with the key attributed

- **WHEN** a mutating primitive completes
- **THEN** an audit event exists attributing the action to the token's owner and naming the
  token used, indistinguishable in coverage from the same action performed in the UI

#### Scenario: Read call

- **WHEN** an agent reads through a tool
- **THEN** the audit trail records the owner, token, tool name and outcome

### Requirement: The agent surface reflects the installation's enabled features

The agent surface SHALL offer only tools, catalogue entries and help text contributed by enabled features and active plugins, and offered to the caller by its token's tool allow-list and the installation's read-only mode. A tool not offered SHALL NOT be listed, SHALL NOT be described in the help tool or the tool catalogue resource, and SHALL NOT be referenced by the server instructions the caller receives. A runbook prompt whose steps name a tool of a disabled feature SHALL omit those steps and say that the feature is disabled on this installation. When a plugin is activated, updated or deactivated, its tools SHALL join, change in, or leave the listed surface and the catalogue without a restart. The server instructions SHALL state that installed plugins may add tools and that the help tool lists them.

#### Scenario: A disabled feature's tools are absent

- **WHEN** the tool list and the tool catalogue are read on an installation with the SQL feature disabled
- **THEN** no SQL tool is listed or described, and the catalogue matches the listed tools exactly

#### Scenario: A restricted token sees only its tools everywhere

- **WHEN** a token restricted to two tools reads the tool list, the help tool, the tool catalogue and the server instructions
- **THEN** only those two tools and the help tool are named, and the catalogue matches the listed tools exactly

#### Scenario: A runbook states a missing feature instead of naming its tool

- **WHEN** a runbook prompt is retrieved whose steps would use a disabled feature's tool
- **THEN** the prompt omits that step and states the feature is disabled on this installation

#### Scenario: An activated plugin's tools are listed and described

- **WHEN** a plugin contributing one tool is activated
- **THEN** the tool list and the help tool include it without a restart, and the catalogue still matches the listed tools exactly

#### Scenario: A tool call during a plugin update is answered

- **WHEN** a plugin tool is called while its plugin is in brief maintenance
- **THEN** the call returns an error result stating the plugin is updating and when to retry

## ADDED Requirements

### Requirement: A token can be restricted to named MCP tools
A token SHALL optionally carry an allow-list of MCP tool names; calls to other tools SHALL be
denied exactly as a call to a tool that does not exist, without revealing them. The help tool
SHALL always be offered. An empty allow-list SHALL mean every tool the token's grants permit.

#### Scenario: Tool outside the list
- **WHEN** a restricted token calls another tool
- **THEN** the call is denied as an unknown tool and audited

#### Scenario: Discovery
- **WHEN** a restricted token lists tools
- **THEN** only allowed tools and the help tool are listed

### Requirement: MCP can be made read-only for the whole installation
An administrator SHALL be able to set the agent surface read-only for the whole installation; a
mutating call SHALL then be refused with a result stating the installation is read-only. A
read-only token SHALL remain a token granted only read permissions, with no second read-only flag.

#### Scenario: Global read-only
- **WHEN** global read-only is on
- **THEN** no token can perform a mutating primitive, even with a dry-run confirmation

#### Scenario: Read-only token
- **WHEN** a token granted only read permissions attempts a mutation through MCP
- **THEN** it is refused by its grants and audited

#### Scenario: Mutating tools hidden
- **WHEN** global read-only is on and an agent lists tools
- **THEN** mutating tools are not offered
