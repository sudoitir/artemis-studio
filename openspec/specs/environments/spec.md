# environments Specification

## Purpose

Defines environments as a named grouping of clusters, used for organizing the
cluster list and as a unit of permission scoping.

## Requirements

### Requirement: Environments can be created, listed, renamed, and removed

The system SHALL allow creating an environment with a unique name, a display
colour, and a sort order, and SHALL allow listing, renaming, recolouring,
reordering, and removing an environment.

#### Scenario: Environment is created

- **WHEN** an administrator creates an environment with a name and colour
- **THEN** the environment exists and can be assigned to clusters

#### Scenario: Duplicate name is rejected

- **WHEN** an administrator creates an environment with a name already in use
- **THEN** the request is rejected

### Requirement: A cluster belongs to at most one environment

The system SHALL allow assigning a cluster to an environment or clearing that
assignment, and cluster views SHALL report the assigned environment's id,
name, and colour when present.

#### Scenario: Cluster is assigned to an environment

- **WHEN** a cluster is assigned to an environment
- **THEN** the cluster's view reports that environment's id, name, and colour

### Requirement: Removing an environment does not remove its clusters

The system SHALL, when an environment is removed, clear the environment
assignment of every cluster that belonged to it and remove any permission
grant scoped to that environment, rather than removing the clusters.

#### Scenario: Clusters survive environment removal

- **WHEN** an environment containing clusters is removed
- **THEN** those clusters continue to exist with no environment assigned

#### Scenario: Grants scoped to a removed environment are removed

- **WHEN** an environment with a permission grant scoped to it is removed
- **THEN** that grant no longer exists
