## MODIFIED Requirements

### Requirement: A user can mint a named, revocable API token

The system SHALL allow an authenticated user to create an API token with a
name and an expiry, disclosed to the user in full exactly once at
creation, and stored only in a form from which the original value cannot be
recovered. The system SHALL allow listing a user's own tokens by name,
creation time, effective expiry, last-used time, grants and MCP tool allow-list without
disclosing their value, and allow revoking a token immediately.

The expiry SHALL be required and SHALL NOT lie beyond the installation's maximum token
lifetime from the time of minting.

Minting SHALL let the user choose the token's grants — permission and scope — at creation.
The choice SHALL be offered from the permissions the minting user actually holds, so the
interaction never presents a grant that would be discarded. A token minted with no grants is
a valid but powerless token; the system SHALL NOT silently produce one when the user was
given no opportunity to choose. Minting SHALL also let the user optionally restrict the token
to named MCP tools, chosen from the tools the user can use.

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

#### Scenario: A token without an expiry is refused

- **WHEN** a user mints a token with no expiry
- **THEN** the request is refused

#### Scenario: Revoked token stops authenticating

- **WHEN** a user revokes one of their tokens
- **THEN** a subsequent request presenting that token is rejected

#### Scenario: Expired token stops authenticating

- **WHEN** a token's effective expiry has passed
- **THEN** a request presenting that token is rejected

## ADDED Requirements

### Requirement: A token can be rotated with an overlap
A token owner SHALL be able to rotate a token, receiving a new secret, disclosed once, while the
old secret stays valid for an administrator-set overlap window (24 hours by default). Rotating
again SHALL end the previous overlap. A revoked or expired token SHALL NOT be rotatable.

#### Scenario: Overlap
- **WHEN** a token is rotated
- **THEN** both secrets work until the window ends, then only the new one does

#### Scenario: Old secret after window
- **WHEN** the old secret is used after the window
- **THEN** the request is rejected and audited

#### Scenario: Rotation does not extend lifetime
- **WHEN** a token is rotated
- **THEN** the new secret keeps the token's grants, tool allow-list, creation time and expiry,
  and cannot exceed the lifetime cap

### Requirement: Administrators cap token lifetime
An administrator SHALL be able to set a maximum token lifetime, 90 days by default. Studio SHALL
refuse to mint a token that expires later than the cap allows. A token's effective expiry SHALL
be the earlier of its own expiry and its creation time plus the current cap, evaluated on every
request, so lowering the cap shortens existing tokens at once and raising it never extends a
token past its own expiry.

#### Scenario: Too long
- **WHEN** a token is minted beyond the cap
- **THEN** it is refused with the allowed maximum

#### Scenario: Lowering the cap shortens existing tokens
- **WHEN** the cap is lowered below the age of an existing token
- **THEN** the next request with that token is rejected as expired

### Requirement: Tokens and users are rate and concurrency limited
Studio SHALL enforce per-token rate and concurrency limits and a per-user request limit across
that user's tokens, all administrator-set, on requests authenticated by a token, answering excess
requests with 429 and headers that state the limit, the remaining allowance and when it resets.
Requests authenticated by a browser session SHALL NOT be limited by these limits.

#### Scenario: Limit exceeded
- **WHEN** a token exceeds its rate
- **THEN** the response is 429 with the limit headers and a retry hint

#### Scenario: Stolen token flood
- **WHEN** a token is used in a flood
- **THEN** other tokens of the same user remain within their own limits unless the per-user limit is reached

#### Scenario: Allowance is stated on success
- **WHEN** a token request succeeds
- **THEN** the response states the limit, the remaining allowance and when it resets

### Requirement: Token usage is summarised in audit
Studio SHALL provide, per token, a summary of use over the last day, 7 days or 30 days: request
counts and how many were denied, limited or failed, in total and per day.

#### Scenario: Summary
- **WHEN** an owner or administrator opens a token's usage
- **THEN** the summary for the chosen period is shown

### Requirement: Administrators can see and revoke any user's tokens
An administrator with the token administration permission SHALL see every token's owner, name,
grants, tool allow-list, effective expiry and last use, never its value, and SHALL be able to
revoke any token and read its usage. Minting and rotating tokens SHALL stay with their owner on
the account page. Tokens unused for an administrator-set period (30 days by default), or never
used within that period of their creation, SHALL be flagged.

#### Scenario: A leaked token is revoked
- **WHEN** an administrator revokes another user's token
- **THEN** the next request with it is rejected, the owner sees it as revoked, and the revocation is audited

#### Scenario: Without permission
- **WHEN** a caller without the permission lists all tokens
- **THEN** the request is refused

#### Scenario: A stale token
- **WHEN** a token has not been used for longer than the set period
- **THEN** the inventory flags it
