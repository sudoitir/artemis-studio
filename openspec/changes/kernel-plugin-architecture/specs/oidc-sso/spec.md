## MODIFIED Requirements

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
