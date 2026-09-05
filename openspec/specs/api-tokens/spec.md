# api-tokens Specification

## Purpose

Defines personal API tokens that let a user authenticate a script or automated
caller without a browser session, scoped to no more than that user's own
grants.

## Requirements

### Requirement: A user can mint a named, revocable API token

The system SHALL allow an authenticated user to create an API token with a
name and an optional expiry, disclosed to the user in full exactly once at
creation, and stored only in a form from which the original value cannot be
recovered. The system SHALL allow listing a user's own tokens by name,
creation time, expiry, and last-used time without disclosing their value, and
allow revoking a token immediately.

#### Scenario: Token value is shown once

- **WHEN** a user creates an API token
- **THEN** the full token value is returned in that response and no later read
  of that token discloses it

#### Scenario: Revoked token stops authenticating

- **WHEN** a user revokes one of their tokens
- **THEN** a subsequent request presenting that token is rejected

#### Scenario: Expired token stops authenticating

- **WHEN** a token's expiry has passed
- **THEN** a request presenting that token is rejected

### Requirement: A token's grants cannot exceed its owner's grants

The system SHALL allow a token to be narrowed to a subset of its owner's
permissions and scopes at creation, and SHALL evaluate every request made with
a token against the intersection of the token's configured grants and its
owner's current grants, so that narrowing or disabling the owner's access
immediately narrows or disables the token's access.

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

### Requirement: Token authentication is a distinct path from session login

The system SHALL authenticate a request bearing a valid token independently of
any session cookie, and mutating requests authenticated by token SHALL NOT be
subject to the session's cross-site request forgery protection.

#### Scenario: Token authenticates without a session

- **WHEN** a request presents a valid token and no session cookie
- **THEN** the request is authenticated as the token's owner, narrowed as
  configured

### Requirement: Token use is attributed in the audit trail

Every audited action performed via a token SHALL record the owning user and
the token's name.

#### Scenario: Audit row names the token

- **WHEN** a mutation is performed using an API token
- **THEN** the resulting audit event records the owning user and the token
  name used
