# oidc-sso Specification

## Purpose

Defines single sign-on via an external OpenID Connect identity provider,
automatic provisioning of first-time users, and mapping an identity provider's
group claim to Studio roles.

## Requirements

### Requirement: A user can log in via an external identity provider

The system SHALL support authenticating a user through an OpenID Connect
authorization-code login with a configured identity provider, establishing the
same kind of session a local login establishes. Local username-and-password
login SHALL remain available alongside single sign-on.

#### Scenario: Successful SSO login establishes a session

- **WHEN** a user completes login at the configured identity provider
- **THEN** a Studio session is established for that user, equivalent to a
  local login

#### Scenario: Local login remains available

- **WHEN** single sign-on is configured
- **THEN** a local user can still log in with a username and password

### Requirement: A first-time identity-provider login provisions a user

The system SHALL, on a successful single sign-on login by an identity not
previously seen, create a user account for that identity with no password set,
so it can only ever be authenticated via the identity provider.

#### Scenario: First SSO login creates an account

- **WHEN** a subject not previously seen completes single sign-on login
- **THEN** a user account for that subject is created and a session is
  established

#### Scenario: Subsequent SSO logins reuse the account

- **WHEN** a previously-seen subject logs in again via single sign-on
- **THEN** the existing account is used and no duplicate account is created

### Requirement: Role grants are derived from an identity provider claim

The system SHALL allow configuring a mapping from a value of a chosen identity
token claim to a role grant at a chosen scope, and SHALL re-apply the
configured mapping to a user's grants on every single sign-on login, so that a
change in the identity provider's group membership takes effect on the user's
next login. A user matching no configured mapping SHALL receive a configured
default role, or SHALL be refused login if no default role is configured.

#### Scenario: A claim value maps to a role

- **WHEN** a user's identity token carries a claim value with a configured
  mapping to a role
- **THEN** that role grant is applied to the user on that login

#### Scenario: A changed claim value updates grants on next login

- **WHEN** a returning user's claim values differ from their previous login
- **THEN** their role grants are updated to match the current mapping on that
  login

#### Scenario: Unmapped user gets the default role

- **WHEN** a user's claim values match no configured mapping and a default
  role is configured
- **THEN** the user is granted the default role

#### Scenario: Unmapped user is refused with no default configured

- **WHEN** a user's claim values match no configured mapping and no default
  role is configured
- **THEN** the login is refused
