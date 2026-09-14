## ADDED Requirements

### Requirement: An audit event outlives what it describes

An audit event SHALL keep its actor user id, cluster id, node id, target name and cluster name exactly as recorded, even after that user, cluster or node is removed. Removing a user, cluster or node SHALL NOT modify or delete any audit event. The audit read for a removed cluster SHALL remain available to a caller holding a global grant for the audit read.

#### Scenario: Removing a cluster keeps its audit trail intact

- **WHEN** a cluster with recorded audit events is removed
- **THEN** its audit events still carry that cluster's id and name and are returned by the audit read to a globally granted caller

#### Scenario: Removing a user keeps attribution

- **WHEN** a user who performed audited actions is deleted
- **THEN** those audit events still record that user's username and user id
