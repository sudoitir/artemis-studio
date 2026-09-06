## MODIFIED Requirements

### Requirement: A user can mint a named, revocable API token

The system SHALL allow an authenticated user to create an API token with a
name and an optional expiry, disclosed to the user in full exactly once at
creation, and stored only in a form from which the original value cannot be
recovered. The system SHALL allow listing a user's own tokens by name,
creation time, expiry, and last-used time without disclosing their value, and
allow revoking a token immediately.

Minting SHALL let the user choose the token's grants — permission and scope — at creation.
The choice SHALL be offered from the permissions the minting user actually holds, so the
interaction never presents a grant that would be discarded. A token minted with no grants is
a valid but powerless token; the system SHALL NOT silently produce one when the user was
given no opportunity to choose.

#### Scenario: Token value is shown once

- **WHEN** a user creates an API token
- **THEN** the full token value is returned in that response and no later read
  of that token discloses it

#### Scenario: Grants are chosen at creation

- **WHEN** a user mints a token choosing a permission at a cluster scope
- **THEN** the created token carries that grant and can immediately exercise it

#### Scenario: Only the minter's own grants are offered

- **WHEN** a user without a permission opens the minting interaction
- **THEN** that permission is not offered as a choice

#### Scenario: Revoked token stops authenticating

- **WHEN** a user revokes one of their tokens
- **THEN** a subsequent request presenting that token is rejected

#### Scenario: Expired token stops authenticating

- **WHEN** a token's expiry has passed
- **THEN** a request presenting that token is rejected

### Requirement: Token authentication is a distinct path from session login

The system SHALL authenticate a request bearing a valid token independently of
any session cookie, and mutating requests authenticated by token SHALL NOT be
subject to the session's cross-site request forgery protection.

Token authentication SHALL be the credential for every non-browser surface the system
exposes, including the MCP endpoint, so that there is exactly one credential kind and one
authorization model for automated callers.

#### Scenario: Token authenticates without a session

- **WHEN** a request presents a valid token and no session cookie
- **THEN** the request is authenticated as the token's owner, narrowed as
  configured

#### Scenario: The same token authenticates the MCP endpoint

- **WHEN** a valid token is presented to the MCP endpoint
- **THEN** the call is authenticated as the token's owner with the same narrowed grants as
  on any other endpoint
