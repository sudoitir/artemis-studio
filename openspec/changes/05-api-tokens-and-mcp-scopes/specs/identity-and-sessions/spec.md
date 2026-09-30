## MODIFIED Requirements

### Requirement: A user has an account page for their own identity and credentials

The system SHALL provide every authenticated user, regardless of role, a self-service
account surface reachable from the application's user menu. It SHALL show who the user is
signed in as, the source of that identity, and a summary of the grants they hold; and it
SHALL be the single place a user manages their own password and mints, rotates and revokes
their own API keys.

The administration surface SHALL NOT mint or rotate API keys. It SHALL offer holders of the
token administration permission an inventory of every user's keys, showing metadata only,
with revocation and stale flags, so a leaked key can be stopped without its owner.

#### Scenario: Every user reaches their account

- **WHEN** a user with no administrative permission opens the user menu
- **THEN** the account surface is offered and opens

#### Scenario: The account surface shows the caller's own identity and grants

- **WHEN** a user opens their account
- **THEN** their username, the source of their identity, and the grants they hold are shown

#### Scenario: Keys are managed only from the account surface

- **WHEN** a user with the token administration permission opens the key inventory on the administration surface
- **THEN** every user's keys are listed with revoke, and no mint or rotate action is offered
