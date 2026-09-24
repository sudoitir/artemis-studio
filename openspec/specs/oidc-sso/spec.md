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

The system SHALL identify an externally authenticated user by the pair of provider identifier and subject. On a successful login by a pair not previously seen, the system SHALL create a user account for it with no password set, so it can only ever be authenticated through that provider. The same subject from two different providers SHALL be two different accounts.

#### Scenario: First SSO login creates an account

- **WHEN** a subject not previously seen for a provider completes single sign-on login
- **THEN** a user account for that provider and subject is created and a session is established

#### Scenario: Subsequent SSO logins reuse the account

- **WHEN** a previously seen provider and subject log in again
- **THEN** the existing account is used and no duplicate account is created

#### Scenario: The same subject from another provider is a separate account

- **WHEN** a subject already provisioned through one provider logs in through a different provider
- **THEN** a separate account is provisioned for the second provider

### Requirement: Role grants are derived from an identity provider claim

The system SHALL allow configuring, per identity provider, a mapping from a group value that provider reports to a role grant at a chosen scope. The mapping SHALL be managed through `/api/v1/identity/providers/{providerId}/group-mappings`, which replaces `/api/v1/oidc/mappings`. For OpenID Connect providers, group values come from the configured identity token claim.

The configured mapping SHALL be re-applied to a user's grants on every login through that provider, so a change in group membership takes effect at the user's next login. A user matching no mapping of their provider SHALL receive that provider's configured default role, or SHALL be refused login if no default role is configured.

#### Scenario: A claim value maps to a role

- **WHEN** a user's identity token carries a claim value with a configured mapping for that provider
- **THEN** that role grant is applied to the user on that login

#### Scenario: A changed claim value updates grants on next login

- **WHEN** a returning user's claim values differ from their previous login
- **THEN** their role grants are updated to match the current mapping on that login

#### Scenario: Unmapped user gets the default role

- **WHEN** a user's claim values match no configured mapping and a default role is configured
- **THEN** the user is granted the default role

#### Scenario: Unmapped user is refused with no default configured

- **WHEN** a user's claim values match no configured mapping and no default role is configured
- **THEN** the login is refused

#### Scenario: Mappings are scoped to their provider

- **WHEN** a group value is mapped for one provider and a user of another provider reports the same group value
- **THEN** the mapping is not applied to that user

#### Scenario: The previous mapping endpoint is gone

- **WHEN** a client calls `/api/v1/oidc/mappings`
- **THEN** the response is `404`

### Requirement: Step-up re-authentication through the identity provider

A user signed in through an identity provider SHALL satisfy a fresh-authentication requirement by re-authenticating with that same provider, which the system SHALL ask to prompt for login and to accept an authentication no older than 5 minutes. The step-up SHALL succeed only when the returning identity is the same provider and subject as the session's, and the provider reports an authentication time within the last 5 minutes; when the provider reports no authentication time the step-up SHALL fail. A successful step-up SHALL keep the user's session and return them to where they started it.

#### Scenario: A different account cannot satisfy step-up

- **WHEN** a user starts a step-up and completes it at the provider as a different subject
- **THEN** the step-up fails and the session's fresh-authentication time is unchanged

#### Scenario: A provider without authentication time fails closed

- **WHEN** the provider's response carries no authentication time
- **THEN** the step-up fails and states that the identity provider does not report one
