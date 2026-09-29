## ADDED Requirements

### Requirement: A token can be rotated with an overlap
A token owner SHALL be able to rotate a token, receiving a new secret while the old secret stays valid for a bounded overlap window.

#### Scenario: Overlap
- **WHEN** a token is rotated
- **THEN** both secrets work until the window ends, then only the new one does

#### Scenario: Old secret after window
- **WHEN** the old secret is used after the window
- **THEN** the request is rejected and audited

#### Scenario: Rotation does not extend lifetime
- **WHEN** a token is rotated
- **THEN** the new secret keeps the token's grants and expiry and cannot exceed the lifetime cap

### Requirement: Administrators cap token lifetime
An administrator SHALL be able to set a maximum lifetime; Studio SHALL refuse longer tokens and SHALL apply the cap to existing tokens as stated in the policy.

#### Scenario: Too long
- **WHEN** a token is minted beyond the cap
- **THEN** it is refused with the allowed maximum

### Requirement: Tokens and users are rate and concurrency limited
Studio SHALL enforce per-token rate and concurrency limits and per-user request limits, answering excess requests with 429 and headers that state the limit, the remaining allowance and when it resets.

#### Scenario: Limit exceeded
- **WHEN** a token exceeds its rate
- **THEN** the response is 429 with the limit headers and a retry hint

#### Scenario: Stolen token flood
- **WHEN** a token is used in a flood
- **THEN** other tokens of the same user remain within their own limits unless the per-user limit is reached

### Requirement: Token usage is summarised in audit
Studio SHALL provide, per token, a summary of use over a period: request counts, outcomes, denied and limited requests.

#### Scenario: Summary
- **WHEN** an owner or administrator opens a token's usage
- **THEN** the summary for the chosen period is shown

### Requirement: Administrators can see and revoke any user's tokens
An administrator with the right permission SHALL see every token's owner, name, grants, expiry and last use, never its value, and SHALL be able to revoke any token. Minting and managing one's own tokens SHALL stay on the account page. Tokens unused for an administrator-set period SHALL be flagged.

#### Scenario: A leaked token is revoked
- **WHEN** an administrator revokes another user's token
- **THEN** the next request with it is rejected, the owner sees it as revoked, and the revocation is audited

#### Scenario: Without permission
- **WHEN** a caller without the permission lists all tokens
- **THEN** the request is refused

#### Scenario: A stale token
- **WHEN** a token has not been used for longer than the set period
- **THEN** the inventory flags it
