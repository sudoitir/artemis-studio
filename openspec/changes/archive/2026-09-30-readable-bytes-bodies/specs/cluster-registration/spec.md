## MODIFIED Requirements

### Requirement: Clusters can be listed, inspected, and removed

The system SHALL expose reading the list of registered clusters with a
rolled-up health indication, reading one cluster with its nodes, and removing a
cluster. Removal SHALL delete Studio's registration and stored credentials for
that cluster and SHALL NOT attempt any change on the broker itself. Removal
SHALL also close that cluster's Core connections and discard its in-memory
subscription state.

In the frontend, removal SHALL be offered only from the cluster's settings, as the last section of the
cluster's own settings group, and SHALL NOT be offered from the header shown above the cluster's views.
The section SHALL state what removal deletes and that the broker is not touched before it can be armed.
A failed removal SHALL state its cause and leave the operator on the section; a successful removal SHALL
take the operator away from the removed cluster.

#### Scenario: List shows health

- **WHEN** the cluster list is requested
- **THEN** each entry carries a health summary derived from its nodes

#### Scenario: Removal is local only

- **WHEN** a cluster is removed
- **THEN** its rows and credentials are deleted and no broker operation is invoked

#### Scenario: Removal releases Core connections

- **WHEN** a cluster with an active Core subscription is removed
- **THEN** its Core connections are closed and not reopened

#### Scenario: Removal is guarded in the UI

- **WHEN** a user removes a cluster from the frontend
- **THEN** the UI requires the cluster name to be typed to confirm

#### Scenario: Removal lives in settings

- **WHEN** an operator views any page of a cluster
- **THEN** the cluster header offers no remove action, and the cluster's settings contain a Remove
  cluster section

#### Scenario: A failed removal says why

- **WHEN** the removal request fails
- **THEN** the section states the cause and the next action, and the cluster remains registered
