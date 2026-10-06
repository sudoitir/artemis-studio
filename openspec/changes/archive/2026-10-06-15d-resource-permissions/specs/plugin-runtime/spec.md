## ADDED Requirements

### Requirement: A plugin permission declares the scope it acts at

Each permission a plugin declares SHALL state `scope` as `global`, `cluster` or `resource`, and a
`resource` permission SHALL state `resourceKinds` (`queue`, `address`) and MAY state `requires`
(other permissions of the plugin or of Studio). A manifest with any other form, including the
removed `globalOnly` field, SHALL be refused at install, naming the permission. Studio SHALL publish
for plugins a check of a permission on a named queue or address, a filter of a list by such a check,
and the same check for the console SDK; these SHALL give exactly the answers Studio's own checks give.

#### Scenario: A manifest with globalOnly is refused

- **WHEN** a plugin declaring a permission with `globalOnly: true` is uploaded
- **THEN** the upload is refused, naming the permission and the `scope` field to use

#### Scenario: A plugin checks a queue

- **WHEN** a plugin checks its `resource` permission on `orders.in` for an Orders Team Operator whose team role holds it
- **THEN** the check succeeds, and fails for `billing.in`

### Requirement: Work that runs as its owner is checked on every run

A plugin's work that runs later as its owner SHALL declare to Studio the permissions and resources
it needs when it is published or enabled. Studio SHALL refuse the publish when the owner lacks any
of them, naming each, SHALL check them again before each run, and SHALL suspend the work with the
reason when the owner has lost one, resuming it only when the owner holds them again and someone
re-enables it.

#### Scenario: Publishing without send rights on a target

- **WHEN** a user publishes work that sends to `billing.in` without `message:send` on it
- **THEN** the publish is refused, naming `message:send` and `billing.in`

#### Scenario: Losing a right suspends the work

- **WHEN** the owner is removed from the team owning the work's source queue
- **THEN** the work is suspended before its next run, and shows why

## MODIFIED Requirements

### Requirement: A plugin's assistant tools declare the permission Studio enforces

Each assistant tool a plugin declares SHALL name a `permission` (one of the plugin's own permissions) and a `scope` (`resource`, `cluster` or `global`), and MAY declare `params: [{name, values?, shape?, note?}]`. A `resource` tool SHALL also name the argument that holds the queue or address name and its kind. Its `posture` SHALL be `read` or `write`. Before a plugin tool runs, Studio SHALL check the caller's permission, as follows:
- a `cluster` tool's `clusterId` argument is parsed (a missing or malformed one is a malformed call, -32602), and the permission is required on that cluster. A denial hides the cluster exactly as Studio's own tools do.
- a `resource` tool's `clusterId` and resource arguments are parsed the same way, and the permission is required on that resource. A denial hides the resource exactly as Studio's own tools do.
- a `global` tool's permission is required globally, and a denial names it.

The plugin's code SHALL NOT run when the check fails. Activation SHALL be refused, naming the tool, when:
- the registered and declared tools differ;
- a `cluster` or `resource` tool takes no required string argument `clusterId`, or a `resource` tool no required string argument for its resource;
- a tool's `readOnlyHint` is not true exactly when its posture is `read`;
- a tool's scope does not match its permission's scope.

#### Scenario: A tool without a permission is refused
- **WHEN** a plugin declares an assistant tool with no `permission`, or one it does not declare
- **THEN** the install is refused, naming the tool

#### Scenario: A denied cluster tool hides the cluster
- **WHEN** a key without a grant on a cluster calls a plugin's cluster tool for it
- **THEN** the answer is "No such cluster, or this key has no grant on it", and the plugin's method was not invoked

#### Scenario: A denied resource tool hides the resource
- **WHEN** an Orders-only key calls a plugin's resource tool for queue `billing.in`
- **THEN** the answer is the same as for a queue that does not exist, and the plugin's method was not invoked

#### Scenario: A denied global tool names the permission
- **WHEN** a key without `acme-notes:admin` calls a global tool that declares it
- **THEN** the answer names `acme-notes:admin`, and the plugin's method was not invoked

#### Scenario: An allowed call reaches the plugin
- **WHEN** the key's owner holds the declared permission on the cluster
- **THEN** the tool runs with the plugin's class loader

#### Scenario: A read tool that is not read-only is refused
- **WHEN** a tool declared `read` is annotated `readOnlyHint = false`
- **THEN** activation is refused, naming the tool
