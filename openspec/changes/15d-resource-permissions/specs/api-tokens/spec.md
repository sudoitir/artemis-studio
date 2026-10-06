## MODIFIED Requirements

### Requirement: A token's grants cannot exceed its owner's grants

The system SHALL allow a token to be narrowed to a subset of its owner's permissions and scopes at
creation, optionally limited to a queue or address name pattern on a cluster, and SHALL evaluate
every request made with a token against the intersection of the token's configured grants and its
owner's current access (grants, teams and shares), so that narrowing or disabling the owner's
access immediately narrows or disables the token's access. A wildcard in a token grant SHALL allow
whatever the owner holds that the wildcard matches. A token grant scoped to an environment or
cluster SHALL be removed when that environment or cluster is deleted.

#### Scenario: A narrowed token cannot exceed its stated grants

- **WHEN** a token is created limited to a read permission on one cluster
- **THEN** a request with that token to perform a write, or to reach a
  different cluster, is rejected

#### Scenario: Demoting the owner narrows the token

- **WHEN** a token's owner loses a permission the token was granted
- **THEN** a subsequent request with that token can no longer exercise that
  permission

#### Scenario: Disabling the owner disables the token

- **WHEN** a token's owning user account is disabled
- **THEN** a subsequent request with that token is rejected

#### Scenario: A wildcard token grant follows the owner

- **WHEN** a token holds `message:*` on a cluster and its owner holds only `message:read` there
- **THEN** the token may read messages there and may not send them

#### Scenario: A token limited to a pattern

- **WHEN** a token is limited to `message:read` on queues matching `orders.#`
- **THEN** it may browse `orders.in` and receives not found for `orders2.in` even when its owner may read it

#### Scenario: Deleting a cluster removes its token grants

- **WHEN** a cluster is deleted
- **THEN** no token grant refers to it
