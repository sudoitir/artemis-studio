## MODIFIED Requirements

### Requirement: Permissions are named strings grouped into roles

The system SHALL represent a permission as a string naming a resource and an action, and SHALL group permissions into named roles. A role SHALL support a wildcard permission that grants every action, and a wildcard scoped to one resource that grants every action on that resource. The system SHALL provide a read of every permission string the application checks, for use when building a role. That read SHALL be assembled from one catalogue: the permissions the installation's enabled features declare followed by those every active plugin declares. Each entry SHALL be attributed to the feature or plugin that declares it. Every reader of the catalogue, including the role editor, the API-key picker and the feature manifest, SHALL use that same catalogue.

A role MAY hold a permission of a feature that is currently disabled, or of a plugin that is inactive or removed. Holding it SHALL grant nothing while its owner is disabled or inactive: every permission check for it SHALL fail, whatever the role or wildcard it comes through. It SHALL take effect unchanged if the owner is enabled or activated again.

#### Scenario: A role wildcard grants all actions on a resource

- **WHEN** a role holds a resource-scoped wildcard permission
- **THEN** every action on that resource is permitted for a user holding that role at the matching scope

#### Scenario: The full-access wildcard grants everything

- **WHEN** a role holds the full-access wildcard permission
- **THEN** every permission check for a user holding that role at the matching scope succeeds

#### Scenario: The catalogue lists only enabled features' permissions

- **WHEN** the permission catalogue is read on an installation with one feature disabled
- **THEN** every permission of the enabled features appears, attributed to its feature, and none of the disabled feature's permissions appear

#### Scenario: A role keeps a disabled feature's permission

- **WHEN** a feature is disabled and a custom role holds one of its permissions
- **THEN** the role is unchanged, every check of that permission fails, and the permission is effective again once the feature is re-enabled

#### Scenario: A plugin's permissions can be granted

- **WHEN** a plugin that declares permissions is active and an administrator reads the catalogue
- **THEN** each of the plugin's permissions appears attributed to the plugin, and a role saved with them holds them

#### Scenario: A plugin activated while the editor is open

- **WHEN** a plugin is activated while an administrator has the role editor open, and the administrator reloads the editor
- **THEN** the plugin's permissions are offered

#### Scenario: A removed plugin's permissions are no longer offered

- **WHEN** a plugin is deactivated or removed
- **THEN** the catalogue no longer lists its permissions, and roles that hold them keep them unchanged

### Requirement: Three built-in roles exist and cannot be altered

The system SHALL provide built-in Administrator, Operator and Viewer roles and built-in Team Viewer, Team Operator and Team Admin roles, and SHALL prevent their permissions, name, or existence from being changed or deleted. Administrator SHALL hold every permission. Operator SHALL hold every catalogue permission that acts at cluster or resource scope except those that change broker configuration, settings, environments, users, roles, teams, tokens, plugins or data, or reveal governed content in clear. Viewer SHALL hold every read permission. Team Viewer SHALL hold the read permissions that act on a resource; Team Operator SHALL add every message and queue permission that acts on a resource; Team Admin SHALL add `team:admin`. Whenever the catalogue gains a core permission, a test SHALL fail until each built-in role's set is updated or the permission is listed as deliberately excluded. Custom roles with any permission combination MAY be created, changed, and deleted.

#### Scenario: A built-in role cannot be edited

- **WHEN** a caller attempts to change the permissions of a built-in role
- **THEN** the request is rejected

#### Scenario: A custom role can be created

- **WHEN** an administrator creates a role with a chosen name and permission set
- **THEN** the role exists and can be granted to users

#### Scenario: Operator can manage queues

- **WHEN** a user holds Operator on a cluster
- **THEN** the user may create, update, pause and delete queues, write diverts and close connections on that cluster

### Requirement: A grant applies at a global, environment, or cluster scope

The system SHALL allow a role to be granted to a user or a directory group at global scope, at the
scope of one environment, or at the scope of one cluster, and the console SHALL offer all three
scopes wherever a grant is made. A permission check for an action on a specific cluster SHALL
succeed if the user holds a grant of that permission at global scope, at the scope of the
environment containing that cluster, or at the scope of that cluster directly. A grant, a change to
a role, a team membership or a share SHALL take effect on the user's next request, without signing
in again.

#### Scenario: A global grant covers every cluster

- **WHEN** a user holds a role at global scope
- **THEN** the user's permission check succeeds for any cluster

#### Scenario: An environment grant covers its member clusters only

- **WHEN** a user holds a role scoped to one environment
- **THEN** the user's permission check succeeds for clusters in that
  environment and fails for clusters outside it, and the console offers the user's actions on those clusters

#### Scenario: A cluster grant does not extend to other clusters

- **WHEN** a user holds a role scoped to one cluster
- **THEN** the user's permission check fails for a different cluster, even one
  in the same environment

#### Scenario: A new grant applies without re-login

- **WHEN** an administrator grants a signed-in user a role on a cluster
- **THEN** the user's next request on that cluster is allowed what the role holds

### Requirement: Every mutating and cluster-scoped operation is permission-checked

The system SHALL check the calling principal's grants against the specific permission an operation
requires before performing it, and SHALL check a read of cluster-scoped data against a read
permission before returning it. An operation on a broker resource SHALL be checked against that
resource, as the team-access capability defines. A principal without the required permission at
the resolved scope SHALL receive a `403` response, except where a cluster or resource it may not
read is addressed, which SHALL receive a not-found response. A build-time test SHALL fail when an
operation that takes a cluster, queue or address has no check.

#### Scenario: A permitted action succeeds

- **WHEN** a user holding the required permission at the target cluster's
  scope performs that action
- **THEN** the action is performed

#### Scenario: An unpermitted action is rejected

- **WHEN** a user lacking the required permission at the target cluster's
  scope attempts that action
- **THEN** the response is `403` and the action is not performed

#### Scenario: A queue's configuration needs read permission

- **WHEN** a signed-in user without read access to a queue requests its configuration
- **THEN** the response is not found

### Requirement: Cluster listings are filtered to what the caller may see

The system SHALL return, from any cluster listing or cross-cluster summary,
only clusters for which the caller holds a read grant at the resolved scope or on which a team of
theirs owns or receives a pattern. Summary figures SHALL count only resources the caller may read.
A caller with no grant on a specific cluster SHALL receive a not-found response, not a forbidden
response, when addressing that cluster directly.

#### Scenario: List omits ungranted clusters

- **WHEN** a user holding a grant on only one of several registered clusters
  requests the cluster list
- **THEN** only that cluster is returned

#### Scenario: Direct access to an ungranted cluster is not found

- **WHEN** a user with no grant on a specific cluster requests it directly by
  id
- **THEN** the response is a not-found response

#### Scenario: A team member sees the team's clusters

- **WHEN** a user's only access is membership of a team owning patterns on prod
- **THEN** the cluster list holds prod, and prod's totals count only the team's resources

### Requirement: A permission has an owner, a description and the scopes at which it takes effect

Every catalogue entry SHALL state its owning module or plugin, a human description, the scope it acts at (`global`, `cluster` or `resource`), the resource kinds a `resource` permission applies to (`queue`, `address`), and the permissions it requires. A `global` permission SHALL take effect only through a global grant; a `cluster` permission through a global, environment or cluster grant; a `resource` permission through any grant, a team role or a share. A permission's required permissions SHALL be held wherever it is: the role editor SHALL add them with a note when the permission is chosen, and saving a role without them SHALL be refused, naming what is missing. The consistency check SHALL report an entry whose required permission is not in the catalogue, or whose requirements form a cycle.

#### Scenario: An entry lacks a description

- **WHEN** a registered permission has no description
- **THEN** the permission consistency check reports it

#### Scenario: A global-only permission granted at cluster scope

- **WHEN** a role holding a global permission is granted to a user on one cluster
- **THEN** the access check marks that permission as having no effect at that scope, and the role editor marks the permission as global

#### Scenario: A required permission is added

- **WHEN** an administrator adds `queue:purge` to a role that lacks `queue:read`
- **THEN** the editor adds `queue:read` and says why

#### Scenario: A role missing a required permission is refused

- **WHEN** a role holding `queue:purge` without `queue:read` is saved through the API
- **THEN** the save is refused, naming `queue:read`

### Requirement: Effective permissions can be previewed for a user

Studio SHALL compute and return the effective permissions of a chosen user: for each of the user's role grants, team memberships and shares, every permission it gives, at its scope, and its source. A wildcard SHALL be expanded to the catalogue entries it matches, naming the wildcard it came through. Studio SHALL also answer an access check for a user, a cluster and an optional queue or address name: for each permission, whether it is allowed there and every source that allows it. A queue or address detail SHALL show its owning team and which teams and roles may act on it. These reads SHALL require the permission to administer users.

#### Scenario: Preview for a user

- **WHEN** an authorised administrator previews a user
- **THEN** the result lists each effective permission with its scope and source role, team or share

#### Scenario: A wildcard is expanded

- **WHEN** the user holds a role with a resource-scoped wildcard
- **THEN** the preview lists each catalogue permission of that resource, each naming the wildcard as its source

#### Scenario: Access check on a queue

- **WHEN** an administrator checks a user on prod for queue `orders.in`
- **THEN** each permission is listed as allowed or not, and an allowed one names its source, for example "team Orders, Team Operator"

#### Scenario: Preview without permission

- **WHEN** a caller lacks the permission to administer users
- **THEN** the preview is refused and reveals nothing about the user, not even whether the user exists
