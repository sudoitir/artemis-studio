## ADDED Requirements

### Requirement: Request-reply configuration and flows require cluster permission

Reading request-reply expectations or flows for a cluster SHALL require read
permission at that cluster's scope. Creating, updating, or deleting an
expectation SHALL require write permission at that cluster's scope.

#### Scenario: Reading flows requires read permission

- **WHEN** a user without read permission on a cluster requests its
  request-reply flows
- **THEN** the request is rejected

#### Scenario: Creating an expectation requires write permission

- **WHEN** a user without write permission on a cluster attempts to create a
  request-reply expectation for it
- **THEN** the request is rejected
