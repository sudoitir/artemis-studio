## ADDED Requirements

### Requirement: A token can be rotated with an overlap
A token owner SHALL be able to rotate a token, receiving a new secret while the old secret stays valid for a bounded overlap window.

#### Scenario: Overlap
- **WHEN** a token is rotated
- **THEN** both secrets work until the window ends, then only the new one does

#### Scenario: Old secret after window
- **WHEN** the old secret is used after the window
- **THEN** the request is rejected and audited

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
