## REMOVED Requirements

### Requirement: The unauthenticated API is flagged at startup

**Reason**: Superseded by real authentication and authorization — the API no
longer runs unauthenticated, so there is nothing left to warn about.
**Migration**: None. The startup warning is removed with the placeholder
security configuration it described.

## ADDED Requirements

### Requirement: Cluster registration and listing require the matching permission

Registering a cluster SHALL require a global write permission. Listing,
inspecting, or removing a cluster SHALL require the corresponding read or
write permission resolved at that cluster's scope, as defined by the
authorization capability.

#### Scenario: Registration requires global write

- **WHEN** a user without global cluster-write permission attempts to
  register a cluster
- **THEN** the request is rejected

#### Scenario: Listing is scoped to granted clusters

- **WHEN** a user holding a grant on only some registered clusters lists
  clusters
- **THEN** only the clusters they hold a read grant on are returned
