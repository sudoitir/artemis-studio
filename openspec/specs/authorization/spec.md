# authorization Specification

## Purpose

Defines how permissions are granted to users, how a grant's scope (global,
environment, or cluster) is resolved against a specific request, and the
safeguards that keep the last administrator from being locked out.

## Requirements

### Requirement: Permissions are named strings grouped into roles

The system SHALL represent a permission as a string naming a resource and an
action, and SHALL group permissions into named roles. A role SHALL support a
wildcard permission that grants every action, and a wildcard scoped to one
resource that grants every action on that resource. The system SHALL provide
a read of every permission string the application checks, for use when
building a role.

#### Scenario: A role wildcard grants all actions on a resource

- **WHEN** a role holds a resource-scoped wildcard permission
- **THEN** every action on that resource is permitted for a user holding that
  role at the matching scope

#### Scenario: The full-access wildcard grants everything

- **WHEN** a role holds the full-access wildcard permission
- **THEN** every permission check for a user holding that role at the
  matching scope succeeds

### Requirement: Three built-in roles exist and cannot be altered

The system SHALL provide built-in administrator, operator, and viewer roles,
seeded with a fixed set of permissions, and SHALL prevent their permissions,
name, or existence from being changed or deleted. Custom roles with any
permission combination MAY be created, changed, and deleted.

#### Scenario: A built-in role cannot be edited

- **WHEN** a caller attempts to change the permissions of a built-in role
- **THEN** the request is rejected

#### Scenario: A custom role can be created

- **WHEN** an administrator creates a role with a chosen name and permission set
- **THEN** the role exists and can be granted to users

### Requirement: A grant applies at a global, environment, or cluster scope

The system SHALL allow a role to be granted to a user at global scope, at the
scope of one environment, or at the scope of one cluster. A permission check
for an action on a specific cluster SHALL succeed if the user holds a grant of
that permission at global scope, at the scope of the environment containing
that cluster, or at the scope of that cluster directly.

#### Scenario: A global grant covers every cluster

- **WHEN** a user holds a role at global scope
- **THEN** the user's permission check succeeds for any cluster

#### Scenario: An environment grant covers its member clusters only

- **WHEN** a user holds a role scoped to one environment
- **THEN** the user's permission check succeeds for clusters in that
  environment and fails for clusters outside it

#### Scenario: A cluster grant does not extend to other clusters

- **WHEN** a user holds a role scoped to one cluster
- **THEN** the user's permission check fails for a different cluster, even one
  in the same environment

### Requirement: Every mutating and cluster-scoped operation is permission-checked

The system SHALL check the calling principal's grants against the specific
permission an operation requires before performing it, and SHALL check a
read of cluster-scoped data against a read permission before returning it. A
principal without the required permission at the resolved scope SHALL receive
a `403` response.

#### Scenario: A permitted action succeeds

- **WHEN** a user holding the required permission at the target cluster's
  scope performs that action
- **THEN** the action is performed

#### Scenario: An unpermitted action is rejected

- **WHEN** a user lacking the required permission at the target cluster's
  scope attempts that action
- **THEN** the response is `403` and the action is not performed

### Requirement: Cluster listings are filtered to what the caller may see

The system SHALL return, from any cluster listing or cross-cluster summary,
only clusters for which the caller holds a read grant at the resolved scope.
A caller with no grant on a specific cluster SHALL receive a not-found
response, not a forbidden response, when addressing that cluster directly.

#### Scenario: List omits ungranted clusters

- **WHEN** a user holding a grant on only one of several registered clusters
  requests the cluster list
- **THEN** only that cluster is returned

#### Scenario: Direct access to an ungranted cluster is not found

- **WHEN** a user with no grant on a specific cluster requests it directly by
  id
- **THEN** the response is a not-found response

### Requirement: The last global administrator cannot be removed

The system SHALL prevent disabling, deleting, or removing the full-access
grant from the last enabled user holding a full-access grant at global scope,
and SHALL prevent a user from removing their own administrative grant.

#### Scenario: Disabling the sole administrator is rejected

- **WHEN** an administrator attempts to disable the only other enabled
  global-administrator account, leaving none, or attempts to disable their
  own account as the sole administrator
- **THEN** the request is rejected

#### Scenario: An administrator cannot revoke their own administrative grant

- **WHEN** an administrator attempts to remove their own full-access grant
- **THEN** the request is rejected
