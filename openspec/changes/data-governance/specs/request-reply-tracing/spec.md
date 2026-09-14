## MODIFIED Requirements

### Requirement: Request-reply configuration and flows require cluster permission

Reading request-reply expectations or flows for a cluster SHALL require read
permission at that cluster's scope. Reading a flow's captured payloads SHALL
additionally require the message read permission at that cluster's scope; a caller
without it SHALL receive the flow without payloads, and the response SHALL state that
payloads were omitted for lack of permission. Creating, updating, or deleting an
expectation SHALL require write permission at that cluster's scope.

#### Scenario: Reading flows requires read permission

- **WHEN** a user without read permission on a cluster requests its
  request-reply flows
- **THEN** the request is rejected

#### Scenario: Creating an expectation requires write permission

- **WHEN** a user without write permission on a cluster attempts to create a
  request-reply expectation for it
- **THEN** the request is rejected

#### Scenario: Payloads require message read permission

- **WHEN** a user with cluster read but without message read permission opens a flow
  whose payloads were captured
- **THEN** the flow is returned without payloads and states that they were omitted for
  lack of permission

## ADDED Requirements

### Requirement: Captured request-reply payloads are stored governed

A captured request or reply payload SHALL be governed by the content policy before it is stored: sensitive values masked, credentials dropped, and non-credential originals sealed. When read back, it SHALL be shown masked, or in clear with its sensitive values identified for a caller holding clear access.

#### Scenario: A stored reply payload is masked

- **WHEN** a captured reply payload contains an email address and a user without clear access opens the flow
- **THEN** the payload shows the email redacted
