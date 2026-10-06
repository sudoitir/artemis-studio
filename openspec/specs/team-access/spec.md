# team-access Specification

## Purpose
Lets several teams share one cluster: each team owns queue and address name patterns, its members
see and operate only what those patterns cover, and access across teams is an explicit share.

## Requirements

### Requirement: A team owns queue and address name patterns per cluster

Studio SHALL let a holder of `user:admin` create, rename and delete teams, and add or remove a team's
owned patterns. A pattern SHALL name a cluster, a resource kind (queue, address, or both) and a
name pattern in the broker's wildcard syntax: words separated by `.`, `*` matching exactly one word,
`#` matching zero or more words. A pattern SHALL be rejected when some queue or address name on the
same cluster and kind could match both it and a pattern owned by another team; the rejection SHALL
name the other team and its pattern. Deleting a team SHALL remove its patterns, memberships and
shares, and SHALL end the access they gave on the next request.

#### Scenario: A pattern that overlaps another team is refused
- **WHEN** team Orders owns queue pattern `orders.#` on cluster prod and an administrator adds `orders.audit.*` to team Audit on prod
- **THEN** the change is refused, naming team Orders and `orders.#`

#### Scenario: The same pattern on another cluster is allowed
- **WHEN** team Orders owns `orders.#` on prod and team Audit is given `orders.#` on staging
- **THEN** the change is accepted

#### Scenario: Disjoint patterns are allowed
- **WHEN** team Orders owns `orders.#` and team Billing is given `billing.*` on the same cluster
- **THEN** the change is accepted

#### Scenario: A malformed pattern is refused
- **WHEN** a pattern is empty, contains an empty word, or a word mixing a wildcard with other characters
- **THEN** it is refused with a message naming the fault

### Requirement: A team's members hold a team role

A team member SHALL be a user or a directory group, and SHALL hold exactly one team-assignable role
in that team. A team-assignable role SHALL hold only permissions that act on a resource, plus
`team:admin`. Studio SHALL ship Team Viewer, Team Operator and Team Admin as built-in team roles.
A holder of `team:admin` in a team SHALL be able to add, change and remove that team's user members, but
SHALL NOT be able to change the team's patterns or shares, to grant a role that holds a permission they do
not hold themselves in that team, or to change or remove a member whose role holds one. Adding, changing or
removing a directory group as a member SHALL need `user:admin`, because a group can admit users to Studio. A
team role that requires a second factor SHALL apply to its members, directly or through a group, as any role
does, and sessions opened before the member held it SHALL end.

#### Scenario: A group member's users get the team role
- **WHEN** a directory group is a member of team Orders as Team Operator and a user signs in with that group
- **THEN** the user holds Team Operator's permissions on resources Orders owns

#### Scenario: A team admin manages members
- **WHEN** a Team Admin of Orders adds a user to Orders as Team Viewer
- **THEN** the user is a member, and the change is audited

#### Scenario: A team admin cannot add a directory group
- **WHEN** a Team Admin of Orders, without `user:admin`, adds a directory group to Orders
- **THEN** the request is refused, and a user administrator can add it

#### Scenario: A team admin cannot demote or remove someone above them
- **WHEN** a Team Admin of Orders, without `user:admin`, changes or removes a member whose role holds a permission the admin does not hold in Orders
- **THEN** the request is refused

#### Scenario: A team role that requires a second factor applies to its members
- **WHEN** a user is added to a team with a role that requires a second factor
- **THEN** the user must hold one at their next sign-in, and their open sessions end

#### Scenario: A team admin cannot widen the team
- **WHEN** a Team Admin of Orders, without `user:admin`, adds a pattern to Orders
- **THEN** the request is refused

#### Scenario: A non-resource permission cannot enter a team role
- **WHEN** a role marked team-assignable is saved holding `user:admin`
- **THEN** the save is refused, naming the permission

### Requirement: A team can share part of what it owns with another team

Studio SHALL let a holder of `user:admin` share a pattern that lies wholly within a team's owned
patterns with another team at a chosen team role. Members of the receiving team SHALL hold that role
on resources the shared pattern matches, in addition to their own team's rights. A share whose
pattern no longer lies within the owner's patterns SHALL grant nothing until it does again.

#### Scenario: A read-only share
- **WHEN** Orders shares `orders.events.#` with Billing as Team Viewer
- **THEN** a Billing member can see and browse queues matching `orders.events.#` and cannot purge them

#### Scenario: A share outside the owner's patterns is refused
- **WHEN** Orders, owning `orders.#`, is asked to share `billing.#`
- **THEN** the request is refused

### Requirement: Access to a broker resource is decided by platform grants, team ownership and shares

For a permission that acts on a resource, a check on a named queue or address of a cluster SHALL
succeed when the caller holds the permission through any of: a role grant at global scope, at the
scope of the cluster's environment, or at the cluster's scope; a team role in the team that owns
the resource; or a share that covers the resource. Grants only add: nothing removes a right another
grant gives. A resource matched by no team SHALL be reachable only through role grants. Derived
resources SHALL be checked against the resource they belong to: a consumer, message or
dead-letter entry against its queue; a producer or divert against its address. A caller who may read any
resource on a cluster SHALL see that cluster in cluster listings, without gaining any cluster-wide
permission.

#### Scenario: A team member sees only the team's queues
- **WHEN** a user is only a Team Operator of Orders, which owns `orders.#` on prod, and lists queues on prod
- **THEN** only queues whose names match `orders.#` are returned, and the total count counts only them

#### Scenario: An unowned queue needs a platform grant
- **WHEN** queue `legacy.inbox` matches no team and a team-only user requests it
- **THEN** the response is not found

#### Scenario: Two teams' rights add up
- **WHEN** a user is Team Viewer in Orders and Team Operator in Billing
- **THEN** the user may purge `billing.x` and may not purge `orders.x`

#### Scenario: A platform grant covers every resource on the cluster
- **WHEN** a user holds Operator at cluster scope on prod
- **THEN** the user may purge any queue on prod, owned or not

### Requirement: A refused resource looks like a missing one

A request naming a queue, address or derived resource the caller may not read SHALL receive the
same not-found response as a resource that does not exist. A request on a resource the caller may
read, for an action they may not perform, SHALL receive a forbidden response naming the permission
and the resource.

#### Scenario: Probing a foreign queue
- **WHEN** an Orders-only user requests `billing.payments`, which exists
- **THEN** the response is identical to the response for a queue that does not exist

#### Scenario: Visible but not allowed
- **WHEN** a Team Viewer of Orders tries to purge `orders.in`
- **THEN** the response is forbidden and names `queue:purge` and `orders.in`

### Requirement: An operation touching several resources needs the right on each

An operation that reads from one resource and writes to another SHALL be allowed only when the
caller holds the needed permission on every resource it touches, checked before anything is done.
Moving messages SHALL need `message:move` on the source queue and `message:send` on the target
address. Retrying dead letters SHALL need `message:move` on the dead-letter queue and `message:send`
on each original address. A divert SHALL need `divert:write` on both addresses. A bulk operation
SHALL check every resource when it is planned and again when it runs.

#### Scenario: Moving to another team's queue is refused
- **WHEN** an Orders Operator moves messages from `orders.in` to `billing.in` with no share from Billing
- **THEN** the move is refused before any message is moved, and the refusal does not reveal whether `billing.in` exists

#### Scenario: A shared target allows the move
- **WHEN** Billing shares `billing.in` with Orders as Team Operator and the same move is made
- **THEN** the messages are moved

### Requirement: New queues and addresses can only be created inside the caller's patterns

Creating a queue SHALL need `queue:create`, and creating an address `address:create`, on the name
being created: a team member may create only names their teams' patterns cover; a platform grant
covers any name on its scope.

#### Scenario: Creating inside the team's pattern
- **WHEN** an Orders Operator creates queue `orders.retry`
- **THEN** the queue is created and is owned by Orders

#### Scenario: Creating outside the team's pattern
- **WHEN** the same user creates `misc.temp`
- **THEN** the request is refused, naming the patterns the user may create under

### Requirement: Connections and sessions are shown through the resources they touch

A connection or session SHALL be visible to a caller who holds `connection:read` on its cluster, or
who may read at least one queue or address that one of its consumers or producers uses. A caller
without `connection:read` SHALL see only the consumers, producers and addresses of that connection
that they may read, and no other team's names. Closing a connection SHALL need `connection:close`
on the cluster.

#### Scenario: A shared client connection is trimmed
- **WHEN** a connection consumes from `orders.in` and `billing.in` and an Orders-only user lists connections
- **THEN** the connection is listed showing only its `orders.in` consumer

#### Scenario: A team user cannot close connections
- **WHEN** an Orders Operator without `connection:close` on the cluster closes a connection
- **THEN** the request is refused

### Requirement: Teams are managed from a Teams page

The console SHALL offer a Teams page listing every team with its member count, its patterns per
cluster and its shares. A team's page SHALL let an administrator edit patterns with a live preview
of the queues and addresses each pattern matches now and an inline error for an overlap, before
saving; edit members and their roles; edit shares; and list the resources on a cluster that no team
owns, so they can be assigned. A Team Admin SHALL see their own team's page with only the members
editable.

#### Scenario: Live preview of a pattern
- **WHEN** an administrator types `orders.#` for cluster prod
- **THEN** the page shows how many queues and addresses match, with examples, before saving

#### Scenario: Unowned resources
- **WHEN** an administrator opens the unowned list for prod
- **THEN** every queue and address on prod that matches no team's pattern is listed

### Requirement: The console shows ownership and explains refusals

Queue and address lists and detail pages SHALL show the owning team. An action the caller cannot
perform on a resource they can see SHALL be shown disabled with the missing permission and a hint
of who can grant it; an action the caller cannot perform anywhere on the page SHALL be hidden.
Target pickers SHALL offer only addresses the caller may send to. Creating a queue or address SHALL
show the patterns the caller may create under and validate the name as it is typed. A team member
with nothing on a cluster SHALL see an empty state that says so and whom to ask. A resource whose
access is lost while it is open SHALL turn into the not-found state.

#### Scenario: Disabled with reason
- **WHEN** a Team Viewer opens queue `orders.in`
- **THEN** Purge is shown disabled, naming `queue:purge` and suggesting an Orders team admin

#### Scenario: Not in any team on a cluster
- **WHEN** a user with no grant and no team on prod opens prod's queues
- **THEN** the empty state says the user is in no team on prod and should ask a platform administrator
