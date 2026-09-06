## ADDED Requirements

### Requirement: A user has an account page for their own identity and credentials

The system SHALL provide every authenticated user, regardless of role, a self-service
account surface reachable from the application's user menu. It SHALL show who the user is
signed in as, the source of that identity, and a summary of the grants they hold; and it
SHALL be the single place a user manages their own password and their own API keys.

Personal API keys SHALL NOT also be manageable from the administration surface. Managing
one's own credentials is not an administrative act, and two surfaces for one thing is one
too many.

#### Scenario: Every user reaches their account

- **WHEN** a user with no administrative permission opens the user menu
- **THEN** the account surface is offered and opens

#### Scenario: The account surface shows the caller's own identity and grants

- **WHEN** a user opens their account
- **THEN** their username, the source of their identity, and the grants they hold are shown

#### Scenario: Keys are managed only from the account surface

- **WHEN** a user with administrative permission opens the administration surface
- **THEN** personal API key management is not offered there

### Requirement: The account surface explains how to connect an agent

The system SHALL show, on the account surface, what a user needs to connect an MCP client:
the endpoint address for the running instance and a copyable client configuration. It SHALL
NEVER display an existing key's value, which is unrecoverable by design; a placeholder SHALL
stand in unless a key has just been minted in the same interaction.

#### Scenario: Connection details are shown without a key

- **WHEN** a user with no freshly-minted key views the connection helper
- **THEN** the endpoint and a copyable configuration are shown with a placeholder in place
  of the key value

#### Scenario: A freshly minted key is offered in context

- **WHEN** a user mints a key and the value is still in hand
- **THEN** the connection configuration may be copied with that value already in place
