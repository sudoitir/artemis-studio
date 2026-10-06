## ADDED Requirements

### Requirement: Refused requests are audited

Studio SHALL record an audit event for every request refused for lack of a permission, naming the
actor, the permission, the cluster and the resource, and whether the resource was hidden (answered
as not found) or forbidden. Identical refusals by the same actor within one minute SHALL be counted
on one event rather than recorded again. A refused broker change SHALL leave a refused audit event,
not a missing one.

#### Scenario: A refused purge is audited

- **WHEN** a Team Viewer tries to purge `orders.in`
- **THEN** an audit event records the refusal with `queue:purge` and `orders.in`

#### Scenario: Repeated refusals are counted

- **WHEN** the same client is refused the same action 50 times in a minute
- **THEN** one audit event records the refusal with a count of 50

### Requirement: The audit trail shows only what the reader may see

A reader without the permission to read a cluster's whole audit trail (`cluster:read` through a
grant) SHALL see only that cluster's audit events about resources they may read.

#### Scenario: A team member reads audit

- **WHEN** an Orders-only user reads prod's audit trail
- **THEN** only events about Orders' resources are returned
