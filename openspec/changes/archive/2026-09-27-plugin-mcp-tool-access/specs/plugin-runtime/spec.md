## ADDED Requirements

### Requirement: A plugin's assistant tools declare the permission Studio enforces

Each assistant tool a plugin declares SHALL name a `permission` (one of the plugin's own permissions) and a `scope` (`cluster` or `global`), and MAY declare `params: [{name, values?, shape?, note?}]`. Its `posture` SHALL be `read` or `write`. Before a plugin tool runs, Studio SHALL check the caller's permission, as follows:
- a `cluster` tool's `clusterId` argument is parsed (a missing or malformed one is a malformed call, -32602), and the permission is required on that cluster. A denial hides the cluster exactly as Studio's own tools do.
- a `global` tool's permission is required globally, and a denial names it.

The plugin's code SHALL NOT run when the check fails. Activation SHALL be refused, naming the tool, when:
- the registered and declared tools differ;
- a `cluster` tool takes no required string argument `clusterId`;
- a tool's `readOnlyHint` is not true exactly when its posture is `read`.

#### Scenario: A tool without a permission is refused
- **WHEN** a plugin declares an assistant tool with no `permission`, or one it does not declare
- **THEN** the install is refused, naming the tool

#### Scenario: A denied cluster tool hides the cluster
- **WHEN** a key without a grant on a cluster calls a plugin's cluster tool for it
- **THEN** the answer is "No such cluster, or this key has no grant on it", and the plugin's method was not invoked

#### Scenario: A denied global tool names the permission
- **WHEN** a key without `acme-notes:admin` calls a global tool that declares it
- **THEN** the answer names `acme-notes:admin`, and the plugin's method was not invoked

#### Scenario: An allowed call reaches the plugin
- **WHEN** the key's owner holds the declared permission on the cluster
- **THEN** the tool runs with the plugin's class loader

#### Scenario: A read tool that is not read-only is refused
- **WHEN** a tool declared `read` is annotated `readOnlyHint = false`
- **THEN** activation is refused, naming the tool

### Requirement: Discovery shows each plugin tool's access and parameters

`studio_help` (its index and each topic) and `studio://tools` SHALL show every plugin tool's permission and scope, and each declared parameter's values, shape and note.

#### Scenario: Help shows the permission
- **WHEN** `studio_help` is called with a plugin tool's name
- **THEN** it returns the tool's permission, scope and declared parameters

### Requirement: The SDK offers a code editor

The plugin SDK SHALL export `CodeEditor`, which edits YAML or JSON with Studio's editor and colours. It SHALL take a value, a change handler (without one it is read-only), a language, diagnostics located by 1-based line and column with a severity, and a visible label that is its accessible name. It SHALL summarise the diagnostics in a live region.

#### Scenario: Diagnostics are shown and announced
- **WHEN** a plugin passes an error for line 4, column 9
- **THEN** the editor marks that position and announces "1 error" with the first message
