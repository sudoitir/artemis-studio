## ADDED Requirements

### Requirement: Alert rule and channel writes require the matching permission

Creating, updating, deleting, or testing an alert rule or a notification
channel SHALL require a write permission at the target cluster's scope.
Reading alert rules, firings, or channels SHALL require read permission at
that scope, and any cross-cluster firing summary SHALL be filtered to clusters
the caller holds read permission on.

#### Scenario: Rule write requires the write permission

- **WHEN** a user without write permission on a cluster attempts to create an
  alert rule for it
- **THEN** the request is rejected

#### Scenario: Cross-cluster firing summary is filtered

- **WHEN** a user holding read permission on only some clusters requests the
  firing summary
- **THEN** only firings for clusters they hold read permission on are included
