## ADDED Requirements

### Requirement: A plugin can contribute a credential sign-in provider
The plugin API SHALL let an active plugin contribute a credential identity provider. Studio SHALL run every sign-in through it on its one login path, so throttling, lockout, MFA, session issue, audit and group-to-role mapping apply unchanged. The provider SHALL only report whether the credentials match and the external identity.

#### Scenario: Sign-in through a plugin provider
- **WHEN** a user signs in with a plugin provider and valid credentials
- **THEN** a session is issued, the user is provisioned by provider and subject, and their group mappings are applied

#### Scenario: The plugin is deactivated
- **WHEN** the plugin that contributes a provider is deactivated
- **THEN** the provider leaves the login screen, and a login naming it fails exactly like a wrong password

#### Scenario: A provider claims another provider's user
- **WHEN** a plugin provider returns a username equal to a local account's
- **THEN** it is a separate account keyed by the plugin's provider and subject, and never gains the local account's identity or grants

#### Scenario: A provider that fails
- **WHEN** a plugin provider throws or does not answer within a bound
- **THEN** the sign-in fails like a wrong password, the failure is reported in operational health, and local sign-in still works

### Requirement: A plugin provider can end sessions of identities it no longer vouches for
Studio SHALL let a provider report that an external identity is no longer valid, and SHALL then end every session of that user, as revoking access does today.

#### Scenario: A user removed at the source
- **WHEN** a provider reports that a signed-in user's identity is no longer valid
- **THEN** that user's next request is unauthenticated and the event is audited
